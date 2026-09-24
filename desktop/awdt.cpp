/**
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
// awdt: receives files from the WDT Android app on the local network.
//
//   awdt receive <folder> [--auto-accept]   be a target for "Send to a nearby
//                                           device" in the app
//   awdt get <awdt://link> [folder]         download a link shared by the app
//
// Both use WDT for the transfer itself. Around it, small line based
// handshakes (the same as in the app, see android/README.md):
//
// Discovery, UDP on port 22355:
//   app -> broadcast   AWDT/1 DISCOVER
//   awdt -> app        AWDT/1 HERE <tcp port> <auto accept 0|1> <name>
// Push, TCP on the same port:
//   app -> awdt        AWDT/1 PUSH <app public key> <files> <bytes> <name>
//   awdt -> app        AWDT/1 ACCEPT <awdt public key> <wdt url without key>
//                      (or AWDT/1 ERROR <reason>)
//   The public keys are P-256 (base64 SubjectPublicKeyInfo); the WDT
//   encryption key is derived from their ECDH secret, so it's never sent.
//   The app then WDT-sends to the url, at the address it connected to.
// Share links (awdt://<host>:<port>/<key>), TCP to the app:
//   awdt -> app        AWDT/1 HELLO <sha256("awdt-proof" + key)>
//   app -> awdt        AWDT/1 OFFER <files> <bytes>
//   awdt -> app        AWDT/1 RECEIVER <wdt url without key>

#include <arpa/inet.h>
#include <glog/logging.h>
#include <netdb.h>
#include <netinet/in.h>
#include <openssl/core_names.h>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/x509.h>
#include <poll.h>
#include <signal.h>
#include <sys/socket.h>
#include <unistd.h>
#include <wdt/Receiver.h>
#include <wdt/WdtConfig.h>
#include <wdt/util/EncryptionUtils.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <iostream>
#include <memory>
#include <regex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace fs = std::filesystem;
using namespace facebook::wdt;

namespace {

constexpr int kDefaultPort = 22355;  // discovery (UDP) and pushes (TCP)
constexpr int kWdtStartPort = 22356;  // WDT's own default ports
constexpr int kWdtNumPorts = 8;
constexpr const char* kProtocol = "AWDT/1";
// Host placeholder in our wdt url: the app uses the address it connected to
constexpr const char* kReceiverHost = "receiver";

struct Error : std::runtime_error {
  using std::runtime_error::runtime_error;
};

// ------------------------------------------------------------- encoding

std::string toHex(const std::string& bytes) {
  static const char* digits = "0123456789abcdef";
  std::string out;
  for (unsigned char c : bytes) {
    out += digits[c >> 4];
    out += digits[c & 15];
  }
  return out;
}

std::string fromHex(const std::string& hex) {
  std::string out;
  for (size_t i = 0; i + 1 < hex.size(); i += 2) {
    out += static_cast<char>(std::stoi(hex.substr(i, 2), nullptr, 16));
  }
  return out;
}

std::string base64Encode(const std::string& bytes) {
  std::string out(4 * ((bytes.size() + 2) / 3), '\0');
  int n = EVP_EncodeBlock(reinterpret_cast<unsigned char*>(&out[0]),
                          reinterpret_cast<const unsigned char*>(bytes.data()),
                          static_cast<int>(bytes.size()));
  out.resize(n);
  return out;
}

std::string base64Decode(const std::string& text) {
  if (text.empty() || text.size() % 4 != 0) {
    throw Error("bad base64");
  }
  std::string out(3 * text.size() / 4, '\0');
  int n = EVP_DecodeBlock(reinterpret_cast<unsigned char*>(&out[0]),
                          reinterpret_cast<const unsigned char*>(text.data()),
                          static_cast<int>(text.size()));
  if (n < 0) {
    throw Error("bad base64");
  }
  // EVP_DecodeBlock doesn't remove what the padding stands for
  size_t padding = text.size() - text.find_last_not_of('=') - 1;
  out.resize(n - padding);
  return out;
}

std::string sha256(const std::string& data) {
  unsigned char md[EVP_MAX_MD_SIZE];
  unsigned int len = 0;
  if (!EVP_Digest(data.data(), data.size(), md, &len, EVP_sha256(), nullptr)) {
    throw Error("sha256 failed");
  }
  return std::string(reinterpret_cast<char*>(md), len);
}

std::string randomBytes(int n) {
  std::string out(n, '\0');
  if (RAND_bytes(reinterpret_cast<unsigned char*>(&out[0]), n) != 1) {
    throw Error("no randomness");
  }
  return out;
}

std::string humanBytes(int64_t bytes) {
  char buf[32];
  snprintf(buf, sizeof(buf), "%.1f MB", bytes / 1e6);
  return buf;
}

// ------------------------------------------------------------------ ECDH

struct PKeyFree {
  void operator()(EVP_PKEY* k) const {
    EVP_PKEY_free(k);
  }
};
using PKey = std::unique_ptr<EVP_PKEY, PKeyFree>;

PKey newP256Key() {
  PKey key(EVP_PKEY_Q_keygen(nullptr, nullptr, "EC", "P-256"));
  if (!key) {
    throw Error("key generation failed");
  }
  return key;
}

/// DER SubjectPublicKeyInfo, what Java's PublicKey.getEncoded() returns
std::string publicKeyDer(EVP_PKEY* key) {
  int len = i2d_PUBKEY(key, nullptr);
  std::string der(len, '\0');
  unsigned char* p = reinterpret_cast<unsigned char*>(&der[0]);
  i2d_PUBKEY(key, &p);
  return der;
}

PKey parseP256PublicKey(const std::string& der) {
  const unsigned char* p = reinterpret_cast<const unsigned char*>(der.data());
  PKey key(d2i_PUBKEY(nullptr, &p, static_cast<long>(der.size())));
  char group[64] = {0};
  size_t len = 0;
  if (!key || !EVP_PKEY_is_a(key.get(), "EC") ||
      !EVP_PKEY_get_utf8_string_param(key.get(), OSSL_PKEY_PARAM_GROUP_NAME,
                                      group, sizeof(group), &len) ||
      std::string(group) != "prime256v1") {
    throw Error("bad public key");
  }
  return key;
}

std::string ecdh(EVP_PKEY* mine, EVP_PKEY* theirs) {
  std::unique_ptr<EVP_PKEY_CTX, decltype(&EVP_PKEY_CTX_free)> ctx(
      EVP_PKEY_CTX_new(mine, nullptr), EVP_PKEY_CTX_free);
  size_t len = 0;
  if (!ctx || EVP_PKEY_derive_init(ctx.get()) <= 0 ||
      EVP_PKEY_derive_set_peer(ctx.get(), theirs) <= 0 ||
      EVP_PKEY_derive(ctx.get(), nullptr, &len) <= 0) {
    throw Error("key agreement failed");
  }
  std::string secret(len, '\0');
  if (EVP_PKEY_derive(ctx.get(), reinterpret_cast<unsigned char*>(&secret[0]),
                      &len) <= 0) {
    throw Error("key agreement failed");
  }
  secret.resize(len);
  return secret;
}

// --------------------------------------------------------------- sockets

class Connection {
 public:
  explicit Connection(int fd) : fd_(fd) {
    timeval tv{30, 0};
    setsockopt(fd_, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
  }
  ~Connection() {
    ::close(fd_);
  }
  Connection(const Connection&) = delete;
  Connection& operator=(const Connection&) = delete;

  void setTimeout(int seconds) {
    timeval tv{seconds, 0};
    setsockopt(fd_, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
  }

  void send(const std::string& words) {
    const std::string line = std::string(kProtocol) + " " + words + "\n";
    size_t done = 0;
    while (done < line.size()) {
      ssize_t n = ::send(fd_, line.data() + done, line.size() - done, 0);
      if (n <= 0) {
        throw Error("connection lost");
      }
      done += n;
    }
  }

  /// The words after the protocol tag and [expected]
  std::vector<std::string> receive(const std::string& expected) {
    std::string line;
    while (true) {
      size_t nl = buf_.find('\n');
      if (nl != std::string::npos) {
        line = buf_.substr(0, nl);
        buf_.erase(0, nl + 1);
        break;
      }
      if (buf_.size() > 8192) {
        throw Error("message too long");
      }
      char chunk[1024];
      ssize_t n = ::recv(fd_, chunk, sizeof(chunk), 0);
      if (n == 0) {
        throw Error("connection closed");
      }
      if (n < 0) {
        throw Error(errno == EAGAIN ? "timed out" : "connection lost");
      }
      buf_.append(chunk, n);
    }
    if (!line.empty() && line.back() == '\r') {
      line.pop_back();
    }
    std::vector<std::string> words;
    std::istringstream in(line);
    for (std::string w; in >> w;) {
      words.push_back(w);
    }
    if (words.size() < 2 || words[0] != kProtocol) {
      throw Error("not a WDT app: " + line);
    }
    if (words[1] == "ERROR") {
      std::string msg;
      for (size_t i = 2; i < words.size(); ++i) {
        msg += (i > 2 ? " " : "") + words[i];
      }
      throw Error(msg.empty() ? "refused" : msg);
    }
    if (words[1] != expected) {
      throw Error("expected " + expected + ", got " + words[1]);
    }
    return {words.begin() + 2, words.end()};
  }

 private:
  int fd_;
  std::string buf_;
};

std::string peerAddress(const sockaddr_storage& addr) {
  char host[NI_MAXHOST] = "?";
  getnameinfo(reinterpret_cast<const sockaddr*>(&addr), sizeof(addr), host,
              sizeof(host), nullptr, 0, NI_NUMERICHOST);
  return host;
}

int listenOn(int type, int port) {
  int fd = ::socket(AF_INET, type, 0);
  int one = 1;
  setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(INADDR_ANY);
  addr.sin_port = htons(port);
  if (fd < 0 || ::bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr))) {
    throw Error(std::string("can't use port ") + std::to_string(port) + ": " +
                strerror(errno));
  }
  if (type == SOCK_STREAM && ::listen(fd, 4)) {
    throw Error(std::string("can't listen: ") + strerror(errno));
  }
  return fd;
}

int connectTo(const std::string& host, int port) {
  addrinfo hints{};
  hints.ai_socktype = SOCK_STREAM;
  addrinfo* res = nullptr;
  if (getaddrinfo(host.c_str(), std::to_string(port).c_str(), &hints, &res)) {
    throw Error("unknown host " + host);
  }
  std::unique_ptr<addrinfo, decltype(&freeaddrinfo)> guard(res, freeaddrinfo);
  for (addrinfo* ai = res; ai; ai = ai->ai_next) {
    int fd = ::socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
    if (fd < 0) {
      continue;
    }
    timeval tv{10, 0};
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    if (::connect(fd, ai->ai_addr, ai->ai_addrlen) == 0) {
      return fd;
    }
    ::close(fd);
  }
  throw Error("can't reach " + host + ":" + std::to_string(port) +
              " (is it still sharing, on this network?)");
}

// ------------------------------------------------------------- receiving

/// The url without its secret: the other side derives / has the key
std::string urlWithoutKey(const std::string& url) {
  size_t q = url.find('?');
  if (q == std::string::npos) {
    return url;
  }
  std::string out = url.substr(0, q + 1);
  std::istringstream params(url.substr(q + 1));
  bool first = true;
  for (std::string p; std::getline(params, p, '&');) {
    if (p.empty() || strncasecmp(p.c_str(), "enc=", 4) == 0) {
      continue;
    }
    out += (first ? "" : "&") + p;
    first = false;
  }
  return out;
}

/// A WDT receiver with the given key, listening (preferably on WDT's usual
/// ports, easier to allow in a firewall)
std::unique_ptr<Receiver> startReceiver(const fs::path& dir,
                                        const std::string& key,
                                        std::string& url) {
  for (bool staticPorts : {true, false}) {
    WdtTransferRequest req(kWdtStartPort, kWdtNumPorts, dir.string());
    req.hostName = kReceiverHost;
    req.encryptionData = EncryptionParams(ENC_AES128_GCM, key);
    req.ivChangeInterval = WdtOptions::get().iv_change_interval_mb * kMbToB;
    auto receiver = std::make_unique<Receiver>(req);
    WdtOptions& options = receiver->getWdtOptions();
    options.static_ports = staticPorts;
    options.max_accept_retries = 300;  // the app connects right away: 30 s
    // Phones' Wi-Fi can stall for seconds (power saving): don't drop the
    // connections after WDT's default 5 s
    options.read_timeout_millis = 30000;
    options.write_timeout_millis = 30000;
    options.enable_download_resumption = false;
    const WdtTransferRequest& ready = receiver->init();
    if (ready.errorCode != OK && ready.errorCode != FEWER_PORTS) {
      continue;  // ports busy: any free ports
    }
    url = ready.genWdtUrlWithSecret();
    if (receiver->transferAsync() != OK) {
      throw Error("can't start receiving");
    }
    return receiver;
  }
  throw Error("can't listen for the transfer");
}

/// Moves what was received in [staging] to [dest], renaming instead of
/// overwriting. Returns the saved paths.
std::vector<fs::path> moveReceived(const fs::path& staging,
                                   const fs::path& dest) {
  std::vector<fs::path> saved;
  for (const auto& entry : fs::recursive_directory_iterator(staging)) {
    if (!entry.is_regular_file() || entry.path().filename() == ".wdt.log") {
      continue;
    }
    fs::path target = dest / fs::relative(entry.path(), staging);
    fs::create_directories(target.parent_path());
    const fs::path stem = target.stem();
    const fs::path ext = target.extension();
    for (int i = 1; fs::exists(target); ++i) {
      target = target.parent_path() /
               (stem.string() + " (" + std::to_string(i) + ")" + ext.string());
    }
    fs::rename(entry.path(), target);
    saved.push_back(target);
  }
  fs::remove_all(staging);
  return saved;
}

/// Waits for the transfer and saves the files; prints the outcome
bool finishReceive(Receiver& receiver, const fs::path& staging,
                   const fs::path& dest, const std::string& from) {
  std::unique_ptr<TransferReport> report = receiver.finish();
  ErrorCode code = report->getSummary().getErrorCode();
  if (code != OK) {
    fs::remove_all(staging);
    std::cout << "Transfer from " << from << " failed: " << errorCodeToStr(code)
              << std::endl;
    return false;
  }
  std::vector<fs::path> saved = moveReceived(staging, dest);
  char rate[64];
  snprintf(rate, sizeof(rate), " in %.1f s (%.1f MB/s)", report->getTotalTime(),
           report->getThroughputMBps());
  std::cout << "Received " << saved.size() << " file(s), "
            << humanBytes(report->getSummary().getEffectiveDataBytes())
            << " from " << from << rate << ":" << std::endl;
  for (const auto& p : saved) {
    std::cout << "  " << p.string() << std::endl;
  }
  return true;
}

fs::path newStaging(const fs::path& dest) {
  fs::path staging = dest / (".awdt-incoming-" + toHex(randomBytes(4)));
  fs::create_directories(staging);
  return staging;
}

// ----------------------------------------------------------- awdt receive

struct ReceiveConfig {
  fs::path folder;
  bool autoAccept = false;
  std::string name;
  int port = kDefaultPort;
};

bool ask(const std::string& question) {
  if (!isatty(STDIN_FILENO)) {
    return false;
  }
  std::cout << question << " [y/N] " << std::flush;
  pollfd p{STDIN_FILENO, POLLIN, 0};
  if (poll(&p, 1, 60000) <= 0) {
    std::cout << "\nNo answer, declined." << std::endl;
    return false;
  }
  std::string line;
  std::getline(std::cin, line);
  return line == "y" || line == "Y" || line == "yes";
}

void answerDiscovery(int fd, const ReceiveConfig& config) {
  const std::string reply = std::string(kProtocol) + " HERE " +
                            std::to_string(config.port) + " " +
                            (config.autoAccept ? "1" : "0") + " " + config.name;
  while (true) {
    char buf[512];
    sockaddr_storage from{};
    socklen_t fromLen = sizeof(from);
    ssize_t n = recvfrom(fd, buf, sizeof(buf) - 1, 0,
                         reinterpret_cast<sockaddr*>(&from), &fromLen);
    if (n <= 0) {
      continue;
    }
    std::string msg(buf, n);
    if (msg.rfind(std::string(kProtocol) + " DISCOVER", 0) == 0) {
      sendto(fd, reply.data(), reply.size(), 0,
             reinterpret_cast<sockaddr*>(&from), fromLen);
    }
  }
}

void handlePush(int fd, const std::string& from, const ReceiveConfig& config) {
  Connection conn(fd);
  std::vector<std::string> push = conn.receive("PUSH");
  if (push.size() < 3) {
    throw Error("bad request");
  }
  const std::string theirDer = base64Decode(push[0]);
  PKey theirs = parseP256PublicKey(theirDer);
  const std::string files = push[1];
  const int64_t bytes = std::stoll(push[2]);
  std::string sender = from;
  if (push.size() > 3) {
    std::string name;
    for (size_t i = 3; i < push.size(); ++i) {
      name += (i > 3 ? " " : "") + push[i];
    }
    sender = name + " (" + from + ")";
  }
  const std::string what = files + " file(s), " + humanBytes(bytes) + " from " + sender;
  if (config.autoAccept) {
    std::cout << "Receiving " << what << "..." << std::endl;
  } else if (!ask("Accept " + what + "?")) {
    conn.send("ERROR declined by the receiver");
    std::cout << "Declined." << std::endl;
    return;
  }
  PKey mine = newP256Key();
  const std::string myDer = publicKeyDer(mine.get());
  const std::string key =
      sha256("awdt-push" + ecdh(mine.get(), theirs.get()) + theirDer + myDer)
          .substr(0, 16);
  const fs::path staging = newStaging(config.folder);
  std::string url;
  std::unique_ptr<Receiver> receiver;
  try {
    receiver = startReceiver(staging, key, url);
  } catch (const Error& e) {
    fs::remove_all(staging);
    conn.send(std::string("ERROR ") + e.what());
    throw;
  }
  conn.send("ACCEPT " + base64Encode(myDer) + " " + urlWithoutKey(url));
  finishReceive(*receiver, staging, config.folder, sender);
}

int cmdReceive(const ReceiveConfig& config) {
  int udp = listenOn(SOCK_DGRAM, config.port);
  int tcp = listenOn(SOCK_STREAM, config.port);
  std::thread(answerDiscovery, udp, config).detach();
  std::cout << "awdt: receiving in " << config.folder.string() << " as \""
            << config.name << "\" (port " << config.port << ", "
            << (config.autoAccept ? "accepting everything" : "asking first")
            << ")." << std::endl
            << "In the WDT app: Send to a nearby device. Ctrl-C to stop."
            << std::endl;
  while (true) {
    sockaddr_storage from{};
    socklen_t fromLen = sizeof(from);
    int fd = ::accept(tcp, reinterpret_cast<sockaddr*>(&from), &fromLen);
    if (fd < 0) {
      continue;
    }
    const std::string address = peerAddress(from);
    try {
      handlePush(fd, address, config);
    } catch (const std::exception& e) {
      std::cout << "Push from " << address << " failed: " << e.what()
                << std::endl;
    }
  }
}

// --------------------------------------------------------------- awdt get

int cmdGet(const std::string& linkText, const fs::path& folder) {
  static const std::regex linkRe(
      R"(^awdt://(\[[0-9a-fA-F:.]+\]|[^:/\[\]]+):(\d{1,5})/([0-9a-fA-F]{32})/?$)");
  std::smatch m;
  if (!std::regex_match(linkText, m, linkRe)) {
    throw Error("not a valid link (awdt://<host>:<port>/<32 hex digits>)");
  }
  std::string host = m[1];
  if (host.front() == '[') {
    host = host.substr(1, host.size() - 2);
  }
  const int port = std::stoi(m[2]);
  const std::string key = fromHex(m[3]);

  std::cout << "Connecting to " << host << "..." << std::endl;
  Connection conn(connectTo(host, port));
  conn.send("HELLO " + toHex(sha256("awdt-proof" + key)));
  std::vector<std::string> offer = conn.receive("OFFER");
  const std::string from = host;
  std::cout << "Receiving " << (offer.empty() ? "?" : offer[0]) << " file(s), "
            << humanBytes(offer.size() > 1 ? std::stoll(offer[1]) : 0)
            << "..." << std::endl;
  const fs::path staging = newStaging(folder);
  std::string url;
  std::unique_ptr<Receiver> receiver;
  try {
    receiver = startReceiver(staging, key, url);
  } catch (...) {
    fs::remove_all(staging);
    throw;
  }
  conn.send("RECEIVER " + urlWithoutKey(url));
  return finishReceive(*receiver, staging, folder, from) ? 0 : 1;
}

// ------------------------------------------------------------------ main

void usage(std::ostream& out) {
  out << "awdt " << WDT_VERSION_STR
      << ": receive files from the WDT Android app on this network\n\n"
         "Usage:\n"
         "  awdt receive <folder> [--auto-accept] [--name <name>] [--port <port>]\n"
         "      Waits for files sent with \"Send to a nearby device\" in the app,\n"
         "      and saves them in <folder>. Asks before each transfer, unless\n"
         "      --auto-accept (then anyone on the network can send files here).\n"
         "  awdt get <awdt://link> [folder]\n"
         "      Downloads the files shared with a link from the app (default\n"
         "      folder: the current one).\n\n"
         "Options:\n"
         "  -v, --verbose   WDT's logs\n"
         "  --name <name>   shown in the app (default: this computer's name)\n"
         "  --port <port>   discovery and control port (default 22355; the app\n"
         "                  finds receivers on 22355). WDT itself uses 22356-22363\n"
         "                  when free.\n";
}

fs::path prepareFolder(const std::string& arg) {
  fs::path folder = fs::absolute(arg);
  fs::create_directories(folder);
  if (access(folder.c_str(), W_OK) != 0) {
    throw Error("can't write to " + folder.string());
  }
  return fs::canonical(folder);
}

std::string hostName() {
  char name[256] = {0};
  gethostname(name, sizeof(name) - 1);
  return name[0] ? name : "computer";
}

}  // namespace

int main(int argc, char** argv) {
  signal(SIGPIPE, SIG_IGN);
  std::vector<std::string> args(argv + 1, argv + argc);
  bool verbose = false;
  bool autoAccept = false;
  std::string name = hostName();
  int port = kDefaultPort;
  std::vector<std::string> positional;
  try {
    for (size_t i = 0; i < args.size(); ++i) {
      const std::string& a = args[i];
      if (a == "-h" || a == "--help") {
        usage(std::cout);
        return 0;
      } else if (a == "--version") {
        std::cout << "awdt " << WDT_VERSION_STR << std::endl;
        return 0;
      } else if (a == "-v" || a == "--verbose") {
        verbose = true;
      } else if (a == "--auto-accept" || a == "-y") {
        autoAccept = true;
      } else if ((a == "--name" || a == "--port") && i + 1 < args.size()) {
        if (a == "--name") {
          name = args[++i];
        } else {
          port = std::stoi(args[++i]);
        }
      } else if (!a.empty() && a[0] == '-') {
        throw Error("unknown option " + a);
      } else {
        positional.push_back(a);
      }
    }
    FLAGS_logtostderr = true;
    FLAGS_minloglevel = verbose ? google::GLOG_INFO : google::GLOG_ERROR;
    google::InitGoogleLogging("awdt");
    WdtCryptoIntializer crypto;
    std::replace(name.begin(), name.end(), '\n', ' ');

    if (positional.size() == 2 && positional[0] == "receive") {
      ReceiveConfig config;
      config.folder = prepareFolder(positional[1]);
      config.autoAccept = autoAccept;
      config.name = name;
      config.port = port;
      return cmdReceive(config);
    }
    if ((positional.size() == 2 || positional.size() == 3) &&
        positional[0] == "get") {
      return cmdGet(positional[1],
                    prepareFolder(positional.size() == 3 ? positional[2] : "."));
    }
    usage(std::cerr);
    return 2;
  } catch (const std::exception& e) {
    std::cerr << "awdt: " << e.what() << std::endl;
    return 1;
  }
}

/**
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
// JNI bridge between the Kotlin API (com.facebook.wdt) and WDT's Sender and
// Receiver. The Kotlin side of these functions is NativeWdt.kt.

#include <android/log.h>
#include <glog/logging.h>
#include <jni.h>
#include <wdt/Receiver.h>
#include <wdt/Reporting.h>
#include <wdt/Sender.h>
#include <wdt/WdtConfig.h>
#include <wdt/util/EncryptionUtils.h>

#include <atomic>
#include <cerrno>
#include <cstdlib>
#include <limits>
#include <memory>
#include <string>
#include <type_traits>
#include <vector>

using namespace facebook::wdt;

namespace {

JavaVM* gVm = nullptr;

// Cached classes (global refs) and method ids, resolved in JNI_OnLoad
jclass gReportClass;
jmethodID gReportCtor;
jclass gProgressClass;
jmethodID gProgressCtor;
jmethodID gOnProgress;
jclass gWdtExceptionClass;
jmethodID gWdtExceptionCtor;
jclass gStringClass;

constexpr const char* kTag = "WDT";

/// Forwards glog (and so WDT's WLOG) messages to logcat
class LogcatSink : public google::LogSink {
 public:
  void send(google::LogSeverity severity, const char* /* fullFilename */,
            const char* baseFilename, int line,
            const google::LogMessageTime& /* time */, const char* message,
            size_t messageLen) override {
    int prio = ANDROID_LOG_INFO;
    switch (severity) {
      case google::GLOG_WARNING:
        prio = ANDROID_LOG_WARN;
        break;
      case google::GLOG_ERROR:
        prio = ANDROID_LOG_ERROR;
        break;
      case google::GLOG_FATAL:
        prio = ANDROID_LOG_FATAL;
        break;
      default:
        break;
    }
    __android_log_print(prio, kTag, "%s:%d %.*s", baseFilename, line,
                        static_cast<int>(messageLen), message);
  }
};

void initLogging() {
  FLAGS_logtostderr = false;
  FLAGS_alsologtostderr = false;
  // App processes have no useful stderr: logcat only
  FLAGS_stderrthreshold = google::NUM_SEVERITIES;
  FLAGS_minloglevel = google::GLOG_WARNING;
  google::InitGoogleLogging("wdt");
  for (int s = 0; s < google::NUM_SEVERITIES; ++s) {
    // empty base name: no log files
    google::SetLogDestination(static_cast<google::LogSeverity>(s), "");
  }
  // Never destroyed: may still be used by threads during process exit
  google::AddLogSink(new LogcatSink());
}

jclass findClass(JNIEnv* env, const char* name) {
  jclass local = env->FindClass(name);
  if (!local) {
    return nullptr;
  }
  auto global = static_cast<jclass>(env->NewGlobalRef(local));
  env->DeleteLocalRef(local);
  return global;
}

/// JNIEnv for the current thread, attaching WDT's own threads (progress
/// reporting) to the VM on first use and detaching them when they exit.
JNIEnv* getEnv() {
  JNIEnv* env = nullptr;
  if (gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
    return env;
  }
  JavaVMAttachArgs args{JNI_VERSION_1_6, "wdt-native", nullptr};
  if (gVm->AttachCurrentThreadAsDaemon(&env, &args) != JNI_OK) {
    return nullptr;
  }
  struct Detacher {
    ~Detacher() {
      gVm->DetachCurrentThread();
    }
  };
  static thread_local Detacher detacher;
  return env;
}

std::string toString(JNIEnv* env, jstring s) {
  if (!s) {
    return {};
  }
  const char* chars = env->GetStringUTFChars(s, nullptr);
  std::string result(chars);
  env->ReleaseStringUTFChars(s, chars);
  return result;
}

void throwIllegalArgument(JNIEnv* env, const std::string& msg) {
  jclass cls = env->FindClass("java/lang/IllegalArgumentException");
  env->ThrowNew(cls, msg.c_str());
}

void throwWdtException(JNIEnv* env, ErrorCode code, const std::string& msg) {
  jstring jmsg = env->NewStringUTF(
      (msg + ": " + errorCodeToStr(code)).c_str());
  auto ex = static_cast<jthrowable>(env->NewObject(
      gWdtExceptionClass, gWdtExceptionCtor, static_cast<jint>(code), jmsg));
  env->Throw(ex);
}

// ---------------------------------------------------------------- options

template <typename T>
bool parseValue(const std::string& s, T& out) {
  if constexpr (std::is_same_v<T, bool>) {
    if (s == "true" || s == "1") {
      out = true;
    } else if (s == "false" || s == "0") {
      out = false;
    } else {
      return false;
    }
  } else if constexpr (std::is_integral_v<T>) {
    errno = 0;
    char* end = nullptr;
    long long v = strtoll(s.c_str(), &end, 10);
    if (errno || end == s.c_str() || *end ||
        v < std::numeric_limits<T>::min() ||
        v > std::numeric_limits<T>::max()) {
      return false;
    }
    out = static_cast<T>(v);
  } else if constexpr (std::is_floating_point_v<T>) {
    errno = 0;
    char* end = nullptr;
    double v = strtod(s.c_str(), &end);
    if (errno || end == s.c_str() || *end) {
      return false;
    }
    out = static_cast<T>(v);
  } else {
    out = s;
  }
  return true;
}

// All the WdtOptions fields, settable by their (flag) name
#define WDT_JNI_OPTIONS(X)                                                    \
  X(dscp) X(skip_writes) X(ignore_open_errors) X(two_phases)                  \
  X(follow_symlinks) X(start_port) X(num_ports) X(static_ports)               \
  X(buffer_size) X(max_retries) X(sleep_millis) X(backlog)                    \
  X(avg_mbytes_per_sec) X(max_mbytes_per_sec) X(throttler_bucket_limit)       \
  X(throttler_log_time_millis) X(include_regex) X(exclude_regex)              \
  X(prune_dir_regex) X(max_transfer_retries) X(full_reporting)                \
  X(progress_report_interval_millis) X(block_size_mbytes)                     \
  X(accept_timeout_millis) X(max_accept_retries) X(accept_window_millis)      \
  X(read_timeout_millis) X(write_timeout_millis) X(connect_timeout_millis)    \
  X(abort_check_interval_millis) X(disk_sync_interval_mb) X(fsync)            \
  X(throughput_update_interval_millis) X(enable_checksum)                     \
  X(enable_perf_stat_collection) X(transfer_log_write_interval_ms)            \
  X(enable_transfer_log_compaction) X(enable_download_resumption)             \
  X(keep_transfer_log) X(disable_sender_verification_during_resumption)       \
  X(global_sender_limit) X(global_receiver_limit)                             \
  X(namespace_sender_limit) X(namespace_receiver_limit) X(odirect_reads)      \
  X(disable_preallocation) X(resume_using_dir_tree)                           \
  X(open_files_during_discovery) X(overwrite) X(drain_extra_ms)               \
  X(encryption_type) X(encryption_tag_interval_bytes) X(send_buffer_size)     \
  X(receive_buffer_size) X(delete_extra_files) X(skip_fadvise)                \
  X(enable_heart_beat) X(iv_change_interval_mb) X(close_on_exec)

/// Applies {name0, value0, name1, value1...}; throws and returns false on
/// unknown names or unparsable values.
bool applyOptions(JNIEnv* env, jobjectArray nameValues, WdtOptions& options) {
  if (!nameValues) {
    return true;
  }
  jsize n = env->GetArrayLength(nameValues);
  for (jsize i = 0; i + 1 < n; i += 2) {
    auto jname = static_cast<jstring>(env->GetObjectArrayElement(nameValues, i));
    auto jvalue =
        static_cast<jstring>(env->GetObjectArrayElement(nameValues, i + 1));
    const std::string name = toString(env, jname);
    const std::string value = toString(env, jvalue);
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(jvalue);
    bool known = false;
    bool parsed = false;
#define X(field)                                   \
  if (!known && name == #field) {                  \
    known = true;                                  \
    parsed = parseValue(value, options.field);     \
  }
    WDT_JNI_OPTIONS(X)
#undef X
    if (!known) {
      throwIllegalArgument(env, "Unknown WDT option: " + name);
      return false;
    }
    if (!parsed) {
      throwIllegalArgument(env,
                           "Invalid value for WDT option " + name + ": " + value);
      return false;
    }
  }
  return true;
}

// ------------------------------------------------------ reports, progress

jobject makeReport(JNIEnv* env, ErrorCode code, const TransferReport* report) {
  jlong bytes = 0, numFiles = 0, numBlocks = 0;
  jdouble seconds = 0, throughput = 0;
  std::vector<std::string> failed;
  if (report) {
    const TransferStats& summary = report->getSummary();
    bytes = summary.getEffectiveDataBytes();
    numFiles = summary.getNumFiles();
    numBlocks = summary.getNumBlocks();
    seconds = report->getTotalTime();
    throughput = report->getThroughputMBps();
    for (const auto& stats : report->getFailedSourceStats()) {
      failed.push_back(stats.getId());
    }
    for (const auto& dir : report->getFailedDirectories()) {
      failed.push_back(dir);
    }
  }
  jobjectArray jfailed = env->NewObjectArray(
      static_cast<jsize>(failed.size()), gStringClass, nullptr);
  for (size_t i = 0; i < failed.size(); ++i) {
    jstring s = env->NewStringUTF(failed[i].c_str());
    env->SetObjectArrayElement(jfailed, static_cast<jsize>(i), s);
    env->DeleteLocalRef(s);
  }
  return env->NewObject(gReportClass, gReportCtor, static_cast<jint>(code),
                        bytes, numFiles, numBlocks, seconds, throughput,
                        jfailed);
}

/// Calls ProgressListener.onProgress() from WDT's progress thread
class JavaProgressReporter : public ProgressReporter {
 public:
  JavaProgressReporter(const WdtTransferRequest& req, jobject listener)
      : ProgressReporter(req), listener_(listener) {
  }

  ~JavaProgressReporter() override {
    if (JNIEnv* env = getEnv()) {
      env->DeleteGlobalRef(listener_);
    }
  }

  void start() override {
  }

  void progress(const std::unique_ptr<TransferReport>& report) override {
    deliver(report, false);
  }

  void end(const std::unique_ptr<TransferReport>& report) override {
    deliver(report, true);
  }

 private:
  void deliver(const std::unique_ptr<TransferReport>& report, bool done) {
    JNIEnv* env = getEnv();
    if (!env || !report) {
      return;
    }
    jobject progress = env->NewObject(
        gProgressClass, gProgressCtor,
        static_cast<jlong>(report->getSummary().getEffectiveDataBytes()),
        static_cast<jlong>(report->getTotalFileSize()),
        static_cast<jdouble>(report->getThroughputMBps()),
        static_cast<jdouble>(report->getCurrentThroughputMBps()),
        static_cast<jboolean>(done));
    if (progress) {
      env->CallVoidMethod(listener_, gOnProgress, progress);
      env->DeleteLocalRef(progress);
    }
    if (env->ExceptionCheck()) {
      // Can't propagate from here (this may be a WDT thread): log and drop
      __android_log_print(ANDROID_LOG_ERROR, kTag,
                          "Exception thrown by ProgressListener, ignored");
      env->ExceptionDescribe();
      env->ExceptionClear();
    }
  }

  jobject listener_;  // global ref
};

// ------------------------------------------------------------- transfers

/// What a Kotlin WdtSender / WdtReceiver handle points to
struct NativeTransfer {
  std::atomic<bool> abortFlag{false};
  std::shared_ptr<IAbortChecker> abortChecker{
      std::make_shared<WdtAbortChecker>(abortFlag)};
  std::unique_ptr<WdtBase> wdt;
};

NativeTransfer* fromHandle(jlong handle) {
  return reinterpret_cast<NativeTransfer*>(handle);
}

void setListener(JNIEnv* env, NativeTransfer* t, jobject listener) {
  if (!listener) {
    return;
  }
  std::unique_ptr<ProgressReporter> reporter =
      std::make_unique<JavaProgressReporter>(t->wdt->getTransferRequest(),
                                             env->NewGlobalRef(listener));
  t->wdt->setProgressReporter(reporter);
}

// Keeps OpenSSL initialized for the lifetime of the library
WdtCryptoIntializer* gCrypto = nullptr;

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
  gVm = vm;
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }
  gReportClass = findClass(env, "com/facebook/wdt/TransferReport");
  gProgressClass = findClass(env, "com/facebook/wdt/TransferProgress");
  jclass listenerClass = env->FindClass("com/facebook/wdt/ProgressListener");
  gWdtExceptionClass = findClass(env, "com/facebook/wdt/WdtException");
  gStringClass = findClass(env, "java/lang/String");
  if (!gReportClass || !gProgressClass || !listenerClass ||
      !gWdtExceptionClass || !gStringClass) {
    return JNI_ERR;
  }
  gReportCtor = env->GetMethodID(gReportClass, "<init>",
                                 "(IJJJDD[Ljava/lang/String;)V");
  gProgressCtor = env->GetMethodID(gProgressClass, "<init>", "(JJDDZ)V");
  gOnProgress = env->GetMethodID(listenerClass, "onProgress",
                                 "(Lcom/facebook/wdt/TransferProgress;)V");
  gWdtExceptionCtor = env->GetMethodID(gWdtExceptionClass, "<init>",
                                       "(ILjava/lang/String;)V");
  env->DeleteLocalRef(listenerClass);
  if (!gReportCtor || !gProgressCtor || !gOnProgress || !gWdtExceptionCtor) {
    return JNI_ERR;
  }
  initLogging();
  gCrypto = new WdtCryptoIntializer();
  return JNI_VERSION_1_6;
}

// ----------------------------------------------------------------- Wdt

JNIEXPORT jstring JNICALL Java_com_facebook_wdt_NativeWdt_version(JNIEnv* env,
                                                                  jclass) {
  return env->NewStringUTF(WDT_VERSION_STR);
}

JNIEXPORT jint JNICALL Java_com_facebook_wdt_NativeWdt_protocolVersion(JNIEnv*,
                                                                       jclass) {
  return Protocol::protocol_version;
}

JNIEXPORT jstring JNICALL
Java_com_facebook_wdt_NativeWdt_errorName(JNIEnv* env, jclass, jint code) {
  return env->NewStringUTF(errorCodeToStr(static_cast<ErrorCode>(code)).c_str());
}

JNIEXPORT jint JNICALL Java_com_facebook_wdt_NativeWdt_errorCount(JNIEnv*,
                                                                  jclass) {
  return static_cast<jint>(AUTH_ERROR) + 1;
}

JNIEXPORT void JNICALL Java_com_facebook_wdt_NativeWdt_setLogLevel(
    JNIEnv*, jclass, jint minLevel, jint verbosity) {
  FLAGS_minloglevel = minLevel;
  FLAGS_v = verbosity;
}

// ------------------------------------------------------------ Receiver

JNIEXPORT jlong JNICALL Java_com_facebook_wdt_NativeWdt_receiverCreate(
    JNIEnv* env, jclass, jstring directory, jobjectArray options,
    jstring hostName, jstring transferId) {
  auto t = std::make_unique<NativeTransfer>();
  WdtOptions opts;
  opts.copyInto(WdtOptions::get());
  if (!applyOptions(env, options, opts)) {
    return 0;
  }
  WdtTransferRequest req(opts.start_port, opts.num_ports,
                         toString(env, directory));
  req.hostName = toString(env, hostName);
  req.transferId = toString(env, transferId);
  req.ivChangeInterval = opts.iv_change_interval_mb * kMbToB;
  auto receiver = std::make_unique<Receiver>(req);
  receiver->setWdtOptions(opts);
  receiver->setAbortChecker(t->abortChecker);
  t->wdt = std::move(receiver);
  return reinterpret_cast<jlong>(t.release());
}

JNIEXPORT jstring JNICALL Java_com_facebook_wdt_NativeWdt_receiverStart(
    JNIEnv* env, jclass, jlong handle, jobject listener) {
  NativeTransfer* t = fromHandle(handle);
  setListener(env, t, listener);
  // init() binds the ports and returns the request with the connection info
  WdtTransferRequest augmented = t->wdt->init();
  if (augmented.errorCode != OK && augmented.errorCode != FEWER_PORTS) {
    throwWdtException(env, augmented.errorCode, "Receiver setup failed");
    return nullptr;
  }
  ErrorCode code = t->wdt->transferAsync();
  if (code != OK) {
    throwWdtException(env, code, "Receiver start failed");
    return nullptr;
  }
  return env->NewStringUTF(augmented.genWdtUrlWithSecret().c_str());
}

// ----------------------------------------------------------------- Sender

JNIEXPORT jlong JNICALL Java_com_facebook_wdt_NativeWdt_senderCreate(
    JNIEnv* env, jclass, jstring url, jstring directory, jobjectArray options,
    jobjectArray fileNames, jlongArray fileSizes, jintArray fileFds) {
  WdtOptions opts;
  opts.copyInto(WdtOptions::get());
  if (!applyOptions(env, options, opts)) {
    return 0;
  }
  WdtTransferRequest req(toString(env, url));
  if (req.errorCode != OK) {
    throwIllegalArgument(env, "Invalid WDT url: " +
                                  errorCodeToStr(req.errorCode));
    return 0;
  }
  req.directory = toString(env, directory);
  if (fileNames) {
    jsize n = env->GetArrayLength(fileNames);
    std::vector<jlong> sizes(n);
    std::vector<jint> fds(n);
    env->GetLongArrayRegion(fileSizes, 0, n, sizes.data());
    env->GetIntArrayRegion(fileFds, 0, n, fds.data());
    for (jsize i = 0; i < n; ++i) {
      auto jname = static_cast<jstring>(env->GetObjectArrayElement(fileNames, i));
      std::string name = toString(env, jname);
      env->DeleteLocalRef(jname);
      if (fds[i] >= 0) {
        req.fileInfo.emplace_back(fds[i], sizes[i], name);
      } else {
        req.fileInfo.emplace_back(name, sizes[i], opts.odirect_reads);
      }
    }
    // An explicit (even empty) list: don't walk the directory
    req.disableDirectoryTraversal = true;
  }
  auto t = std::make_unique<NativeTransfer>();
  auto sender = std::make_unique<Sender>(req);
  sender->setWdtOptions(opts);
  sender->setAbortChecker(t->abortChecker);
  t->wdt = std::move(sender);
  return reinterpret_cast<jlong>(t.release());
}

JNIEXPORT jobject JNICALL Java_com_facebook_wdt_NativeWdt_senderTransfer(
    JNIEnv* env, jclass, jlong handle, jobject listener) {
  NativeTransfer* t = fromHandle(handle);
  setListener(env, t, listener);
  const WdtTransferRequest& validated = t->wdt->init();
  if (validated.errorCode != OK) {
    return makeReport(env, validated.errorCode, nullptr);
  }
  ErrorCode code = t->wdt->transferAsync();
  if (code != OK) {
    return makeReport(env, code, nullptr);
  }
  std::unique_ptr<TransferReport> report = t->wdt->finish();
  return makeReport(env, report->getSummary().getErrorCode(), report.get());
}

// ------------------------------------------------------- common to both

JNIEXPORT jobject JNICALL Java_com_facebook_wdt_NativeWdt_finish(
    JNIEnv* env, jclass, jlong handle) {
  std::unique_ptr<TransferReport> report = fromHandle(handle)->wdt->finish();
  return makeReport(env, report->getSummary().getErrorCode(), report.get());
}

JNIEXPORT void JNICALL Java_com_facebook_wdt_NativeWdt_abort(JNIEnv*,
                                                                     jclass,
                                                                     jlong handle) {
  fromHandle(handle)->abortFlag = true;
}

JNIEXPORT void JNICALL
Java_com_facebook_wdt_NativeWdt_destroy(JNIEnv*, jclass, jlong handle) {
  delete fromHandle(handle);
}

}  // extern "C"

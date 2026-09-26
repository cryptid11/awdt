# awdt: receive files from your phone

`awdt` is the desktop side of the WDT Android app (`android/library/sample`),
for Linux. The transfers themselves are WDT's.

## Install

Download `awdt-linux-x86_64` from the
[latest release](https://github.com/cryptid11/awdt/releases/tag/latest):

```sh
mkdir -p ~/.local/bin
curl -Lo ~/.local/bin/awdt https://github.com/cryptid11/awdt/releases/download/latest/awdt-linux-x86_64
chmod +x ~/.local/bin/awdt
```

It only needs glibc 2.35 or newer (the other libraries are built in).
To build it instead: `desktop/build.sh` (a C++17 compiler, cmake >= 3.22,
ninja, git, curl, make, perl). This builds `_desktop/install/bin/awdt`, and
`wdt`, WDT's command line tool.

## Receive files sent from the phone

```sh
awdt receive ~/Downloads/WDT --auto-accept
```

In the app, the computer then shows up under **Send**: tap it, choose the
files, and they are saved in `~/Downloads/WDT` (renamed rather than
overwritten if the name is taken). The phone and the computer must be on the
same network.

* Without `--auto-accept`, `awdt` asks in the terminal before each transfer.
  With it, anyone on your network can send you files, so use it on networks
  you trust.
* `--name <name>` changes the name shown in the app (default: the host name).
* To always be ready: `desktop/awdt.service` is a systemd user service (the
  instructions are at its top).

## Download a link shared from the phone

When the app shares files with a link, open it in a browser, or download
with WDT (faster, and encrypted):

```sh
awdt get http://192.168.1.34:40195/482a7b0c9f382014056844c752d3d08a ~/Downloads
```

## Firewall

The phone connects to the computer: if you use a firewall, allow UDP port
22355 (discovery) and TCP ports 22355-22358 (22356-22358 are WDT's), e.g.
`sudo ufw allow 22355/udp && sudo ufw allow 22355:22358/tcp`. If the phone
can't find the computer (some networks block broadcasts), type the computer's
address in the app instead.

## Security

Files are encrypted by WDT (AES-128-GCM). For pushes, the phone and `awdt`
agree on the key with an ECDH key exchange (P-256), so it never goes over the
network; for links, the key is in the link. The key exchange isn't
authenticated: someone able to intercept and modify traffic on your network
could pose as the computer. File paths from the sender are checked: nothing
is written outside the destination folder.

#!/system/bin/sh
# On-device end-to-end test: sends a directory tree over loopback with the
# wdt binary in the same directory, then compares checksums.
# Usage (from host): adb push wdt android/e2e-test.sh /data/local/tmp/
#                    adb shell sh /data/local/tmp/e2e-test.sh [extra wdt flags]
set -e
cd "$(dirname "$0")"
rm -rf e2e && mkdir -p e2e/src/sub/deeper e2e/dst && cd e2e
dd if=/dev/urandom of=src/big bs=1048576 count=200 2>/dev/null
i=1
while [ $i -le 300 ]; do
  dd if=/dev/urandom of=src/sub/f$i bs=$((i * 97)) count=1 2>/dev/null
  i=$((i + 1))
done
echo hello > src/sub/deeper/small
: > src/empty

../wdt -directory dst -transfer_id e2e -start_port 22356 -num_ports 4 "$@" \
  > url.txt 2> recv.log &
RECV=$!
while [ ! -s url.txt ]; do sleep 0.2; done
../wdt -directory src -connection_url "$(cat url.txt)" "$@" 2> send.log
wait $RECV
grep -h "Total sender\|Total receiver" send.log recv.log || true

(cd src && find . -type f -exec md5sum {} + | sort -k2) > a.md5
# .wdt.log is the transfer journal written with -enable_download_resumption
(cd dst && find . -type f ! -name .wdt.log -exec md5sum {} + | sort -k2) > b.md5
if cmp -s a.md5 b.md5; then
  echo "PASS: $(wc -l < a.md5) files identical"
else
  echo "FAIL: trees differ"; diff a.md5 b.md5 | head; exit 1
fi

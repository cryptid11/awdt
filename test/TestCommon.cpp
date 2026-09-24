/**
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

#include <wdt/ErrorCodes.h>
#include <wdt/test/TestCommon.h>

#include <cstdlib>
#include <filesystem>
#include <mutex>
#include <random>

using namespace std;

namespace facebook {
namespace wdt {

uint64_t rand64() {
  static std::default_random_engine randomEngine{std::random_device()()};
  static std::mutex mutex;
  std::lock_guard<std::mutex> lock(mutex);
  return randomEngine();
}

uint32_t rand32() {
  return static_cast<uint32_t>(rand64());
}

std::string tmpDir() {
  const char* dir = getenv("TMPDIR");
  return (dir && *dir) ? dir : "/tmp";
}

TemporaryDirectory::TemporaryDirectory() {
  std::string dir = tmpDir() + "/wdtTest.XXXXXX";
  if (!mkdtemp(&dir[0])) {
    WPLOG(FATAL) << "unable to make " << dir;
  }
  dir_ = dir;
}

TemporaryDirectory::~TemporaryDirectory() {
  std::filesystem::remove_all(dir_);
}
}  // namespace wdt
}  // namespace facebook

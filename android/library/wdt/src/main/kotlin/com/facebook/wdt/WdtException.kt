/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt

import java.io.IOException

/** A transfer could not be set up. Thrown by [WdtReceiver.start]. */
class WdtException internal constructor(code: Int, message: String) : IOException(message) {
    val errorCode: WdtErrorCode = WdtErrorCode.fromCode(code)
}

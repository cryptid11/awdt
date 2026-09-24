/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt

/** Mirrors WDT's ErrorCode enum (ErrorCodes.h), in the same order. */
enum class WdtErrorCode {
    OK,
    ERROR,
    ABORT,
    CONN_ERROR,
    CONN_ERROR_RETRYABLE,
    SOCKET_READ_ERROR,
    SOCKET_WRITE_ERROR,
    BYTE_SOURCE_READ_ERROR,
    FILE_WRITE_ERROR,
    MEMORY_ALLOCATION_ERROR,
    PROTOCOL_ERROR,
    VERSION_MISMATCH,
    ID_MISMATCH,
    CHECKSUM_MISMATCH,
    RESOURCE_NOT_FOUND,
    ABORTED_BY_APPLICATION,
    VERSION_INCOMPATIBLE,
    NOT_FOUND,
    QUOTA_EXCEEDED,
    FEWER_PORTS,
    URI_PARSE_ERROR,
    INCONSISTENT_DIRECTORY,
    INVALID_LOG,
    INVALID_CHECKPOINT,
    NO_PROGRESS,
    TRANSFER_LOG_ACQUIRE_ERROR,
    WDT_TIMEOUT,
    UNEXPECTED_CMD_ERROR,
    ENCRYPTION_ERROR,
    ALREADY_EXISTS,
    GLOBAL_CHECKPOINT_ABORT,
    INVALID_REQUEST,
    SENDER_START_TIMED_OUT,
    AUTH_ERROR;

    companion object {
        @JvmStatic
        fun fromCode(code: Int): WdtErrorCode = entries.getOrElse(code) { ERROR }
    }
}

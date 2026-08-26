/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2015-2018.
 */

#include <stdio.h>
#include <stdarg.h>
#include <time.h>
#include "FileLog.h"
#include "ConnectionsManager.h"

#ifdef ANDROID
#include <android/log.h>
#endif

// Off until something asks for it, in every build. This used to read
// `#ifdef DEBUG_VERSION` - and DEBUG_VERSION is defined unconditionally in
// Defines.h, for every build there is - so a release installed from a store
// wrote the whole protocol to logcat: the server's address, the handshake, and
// every call by name. 767 lines from one launch, before anyone had signed in.
// Any app holding READ_LOGS, any adb session and every bug report got a running
// account of who talks to whom and when. The ciphertext was never at risk; the
// metadata was all of it (#93).
//
// Nothing is lost for a debug build. ConnectionsManager::init turns this on
// when it is given a log path, and the path comes from Java's
// FileLog.getNetworkLogPath(), which is empty unless BuildVars.LOGS_ENABLED -
// so the two sides now follow one switch instead of two, and that switch is
// off in release.
bool LOGS_ENABLED = false;

bool REF_LOGS_ENABLED = false;

FileLog &FileLog::getInstance() {
    static FileLog instance;
    return instance;
}

FileLog::FileLog() {
    pthread_mutex_init(&mutex, NULL);
}

void FileLog::init(std::string path) {
    pthread_mutex_lock(&mutex);
    if (path.size() > 0 && logFile == nullptr) {
        logFile = fopen(path.c_str(), "w");
    }
    pthread_mutex_unlock(&mutex);
}

void FileLog::fatal(const char *message, ...) {
    if (!LOGS_ENABLED) {
        return;
    }
    va_list argptr;
    va_start(argptr, message);

    struct timeval time_now;
    gettimeofday(&time_now, NULL);
    struct tm *now = localtime(&time_now.tv_sec);
#ifdef ANDROID
    __android_log_vprint(ANDROID_LOG_FATAL, "tgnet", message, argptr);
    va_end(argptr);
    va_start(argptr, message);
#else
    printf("%d-%d %02d:%02d:%02d FATAL ERROR: ", now->tm_mon + 1, now->tm_mday, now->tm_hour, now->tm_min, now->tm_sec);
    vprintf(message, argptr);
    printf("\n");
    fflush(stdout);
    va_end(argptr);
    va_start(argptr, message);
#endif
    FILE *logFile = getInstance().logFile;
    if (logFile) {
        fprintf(logFile, "%d-%d %02d:%02d:%02d.%03d FATAL ERROR: ", now->tm_mon + 1, now->tm_mday, now->tm_hour, now->tm_min, now->tm_sec, (int) (time_now.tv_usec / 1000));
        vfprintf(logFile, message, argptr);
        fprintf(logFile, "\n");
        fflush(logFile);
    }

    va_end(argptr);

#ifdef DEBUG_VERSION
    abort();
#endif
}

void FileLog::e(const char *message, ...) {
    if (!LOGS_ENABLED) {
        return;
    }
    va_list argptr;
    va_start(argptr, message);
    struct timeval time_now;
    gettimeofday(&time_now, NULL);
    struct tm *now = localtime(&time_now.tv_sec);
#ifdef ANDROID
    __android_log_vprint(ANDROID_LOG_ERROR, "tgnet", message, argptr);
    va_end(argptr);
    va_start(argptr, message);
#else
    printf("%d-%d %02d:%02d:%02d error: ", now->tm_mon + 1, now->tm_mday, now->tm_hour, now->tm_min, now->tm_sec);
    vprintf(message, argptr);
    printf("\n");
    fflush(stdout);
    va_end(argptr);
    va_start(argptr, message);
#endif
    FILE *logFile = getInstance().logFile;
    if (logFile) {
        fprintf(logFile, "%d-%d %02d:%02d:%02d.%03d error: ", now->tm_mon + 1, now->tm_mday, now->tm_hour, now->tm_min, now->tm_sec, (int) (time_now.tv_usec / 1000));
        vfprintf(logFile, message, argptr);
        fprintf(logFile, "\n");
        fflush(logFile);
    }
    
    va_end(argptr);
}

void FileLog::w(const char *message, ...) {
    if (!LOGS_ENABLED) {
        return;
    }
    va_list argptr;
    va_start(argptr, message);
    struct timeval time_now;
    gettimeofday(&time_now, NULL);
    struct tm *now = localtime(&time_now.tv_sec);
#ifdef ANDROID
    __android_log_vprint(ANDROID_LOG_WARN, "tgnet", message, argptr);
    va_end(argptr);
    va_start(argptr, message);
#else
    printf("%d-%d %02d:%02d:%02d warning: ", now->tm_mon + 1, now->tm_mday, now->tm_hour, now->tm_min, now->tm_sec);
    vprintf(message, argptr);
    printf("\n");
    fflush(stdout);
    va_end(argptr);
    va_start(argptr, message);
#endif
    FILE *logFile = getInstance().logFile;
    if (logFile) {
        fprintf(logFile, "%d-%d %02d:%02d:%02d.%03d warning: ", now->tm_mon + 1, now->tm_mday, now->tm_hour, now->tm_min, now->tm_sec, (int) (time_now.tv_usec / 1000));
        vfprintf(logFile, message, argptr);
        fprintf(logFile, "\n");
        fflush(logFile);
    }
    
    va_end(argptr);
}

void FileLog::d(const char *message, ...) {
    if (!LOGS_ENABLED) {
        return;
    }
    va_list argptr;
    va_start(argptr, message);

    struct timeval time_now;
    gettimeofday(&time_now, NULL);
    struct tm *now = localtime(&time_now.tv_sec);
#ifdef ANDROID
    __android_log_vprint(ANDROID_LOG_DEBUG, "tgnet", message, argptr);
    va_end(argptr);
    va_start(argptr, message);
#else
    printf("%d-%d %02d:%02d:%02d debug: ", now->tm_mon + 1, now->tm_mday, now->tm_hour, now->tm_min, now->tm_sec);
    vprintf(message, argptr);
    printf("\n");
    fflush(stdout);
    va_end(argptr);
    va_start(argptr, message);
#endif
    FILE *logFile = getInstance().logFile;
    if (logFile) {
        fprintf(logFile, "%d-%d %02d:%02d:%02d.%03d debug: ", now->tm_mon + 1, now->tm_mday, now->tm_hour, now->tm_min, now->tm_sec, (int) (time_now.tv_usec / 1000));
        vfprintf(logFile, message, argptr);
        fprintf(logFile, "\n");
        fflush(logFile);
    }
    
    va_end(argptr);
}

static int refsCount = 0;

void FileLog::ref(const char *message, ...) {
    if (!REF_LOGS_ENABLED) {
        return;
    }
    va_list argptr;
    va_start(argptr, message);
    refsCount++;
#ifdef ANDROID
    std::ostringstream s;
    s << refsCount << " refs (+ref): " << message;
    __android_log_vprint(ANDROID_LOG_VERBOSE, "tgnetREF", s.str().c_str(), argptr);
    va_end(argptr);
    va_start(argptr, message);
#endif
    va_end(argptr);
}

void FileLog::delref(const char *message, ...) {
    if (!REF_LOGS_ENABLED) {
        return;
    }
    va_list argptr;
    va_start(argptr, message);
    refsCount--;
#ifdef ANDROID
    std::ostringstream s;
    s << refsCount << " refs (-ref): " << message;
    __android_log_vprint(ANDROID_LOG_VERBOSE, "tgnetREF", s.str().c_str(), argptr);
    va_end(argptr);
    va_start(argptr, message);
#endif
    va_end(argptr);
}
// The bridge between Java and the MLS core.
//
// The cryptography is one piece of Rust shared with the iOS client - written
// once, tested once, and impossible to have right in one client and wrong in
// the other. This file only carries values across: Java arrays in, byte arrays
// out, handles as longs.
//
// Nothing here decides anything. Every failure comes back as null with a reason
// the core recorded, because a crash inside a crypto library is a security
// event and not an error message.

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "mls/include/mls.h"

// Handles cross as jlong. Sixty-four bits hold a pointer on every architecture
// this ships to, and a wrong one is caught by the core rather than by a signal.
static inline void *from_handle(jlong handle) {
    return (void *) (intptr_t) handle;
}

static inline jlong to_handle(const void *pointer) {
    return (jlong) (intptr_t) pointer;
}

// Copies a buffer the core owns into a Java array and gives the memory back.
// An empty buffer becomes null, which is how Java hears "nothing".
static jbyteArray take(JNIEnv *env, struct MlsBuffer buffer) {
    if (buffer.ptr == NULL) {
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize) buffer.len);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) buffer.len, (const jbyte *) buffer.ptr);
    }
    mls_buffer_free(buffer);
    return result;
}

JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_MlsCore_lastError(JNIEnv *env, jclass class) {
    const char *reason = mls_last_error();
    return (*env)->NewStringUTF(env, reason == NULL ? "" : reason);
}

JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_MlsCore_identityNew(JNIEnv *env, jclass class, jbyteArray name) {
    jsize len = (*env)->GetArrayLength(env, name);
    jbyte *bytes = (*env)->GetByteArrayElements(env, name, NULL);
    Identity *identity = mls_identity_new((const unsigned char *) bytes, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, name, bytes, JNI_ABORT);
    return to_handle(identity);
}

JNIEXPORT void JNICALL
Java_org_telegram_messenger_MlsCore_identityFree(JNIEnv *env, jclass class, jlong identity) {
    mls_identity_free((Identity *) from_handle(identity));
}

JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_MlsCore_keyPackage(JNIEnv *env, jclass class, jlong identity) {
    return take(env, mls_identity_key_package((const Identity *) from_handle(identity)));
}

JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_MlsCore_groupCreate(JNIEnv *env, jclass class, jlong identity) {
    return to_handle(mls_group_create((const Identity *) from_handle(identity)));
}

JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_MlsCore_groupJoin(JNIEnv *env, jclass class, jlong identity, jbyteArray welcome) {
    jsize len = (*env)->GetArrayLength(env, welcome);
    jbyte *bytes = (*env)->GetByteArrayElements(env, welcome, NULL);
    Group *group = mls_group_join((const Identity *) from_handle(identity),
                                  (const unsigned char *) bytes, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, welcome, bytes, JNI_ABORT);
    return to_handle(group);
}

JNIEXPORT void JNICALL
Java_org_telegram_messenger_MlsCore_groupFree(JNIEnv *env, jclass class, jlong group) {
    mls_group_free((Group *) from_handle(group));
}

// Adding produces two things that both have to be delivered: the commit for
// everybody already here and the welcome for the newcomer. They come back as a
// two-element array so neither can be dropped by forgetting an out-parameter.
JNIEXPORT jobjectArray JNICALL
Java_org_telegram_messenger_MlsCore_addMember(JNIEnv *env, jclass class, jlong group, jlong identity, jbyteArray keyPackage) {
    jsize len = (*env)->GetArrayLength(env, keyPackage);
    jbyte *bytes = (*env)->GetByteArrayElements(env, keyPackage, NULL);

    struct MlsBuffer commit = {NULL, 0};
    struct MlsBuffer welcome = mls_group_add_member((Group *) from_handle(group),
                                                    (const Identity *) from_handle(identity),
                                                    (const unsigned char *) bytes,
                                                    (size_t) len,
                                                    &commit);
    (*env)->ReleaseByteArrayElements(env, keyPackage, bytes, JNI_ABORT);

    if (welcome.ptr == NULL) {
        mls_buffer_free(commit);
        return NULL;
    }

    jclass byteArrayClass = (*env)->FindClass(env, "[B");
    jobjectArray pair = (*env)->NewObjectArray(env, 2, byteArrayClass, NULL);
    if (pair == NULL) {
        mls_buffer_free(welcome);
        mls_buffer_free(commit);
        return NULL;
    }
    (*env)->SetObjectArrayElement(env, pair, 0, take(env, commit));
    (*env)->SetObjectArrayElement(env, pair, 1, take(env, welcome));
    return pair;
}

JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_MlsCore_encrypt(JNIEnv *env, jclass class, jlong group, jlong identity, jbyteArray plaintext) {
    jsize len = (*env)->GetArrayLength(env, plaintext);
    jbyte *bytes = (*env)->GetByteArrayElements(env, plaintext, NULL);
    struct MlsBuffer out = mls_group_encrypt((Group *) from_handle(group),
                                             (const Identity *) from_handle(identity),
                                             (const unsigned char *) bytes, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, plaintext, bytes, JNI_ABORT);
    return take(env, out);
}

// An empty array means the bytes were a commit that moved the group on rather
// than something to show. Null means it failed, and lastError says why.
JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_MlsCore_decrypt(JNIEnv *env, jclass class, jlong group, jlong identity, jbyteArray ciphertext) {
    jsize len = (*env)->GetArrayLength(env, ciphertext);
    jbyte *bytes = (*env)->GetByteArrayElements(env, ciphertext, NULL);
    unsigned char handshake = 0;
    struct MlsBuffer out = mls_group_decrypt((Group *) from_handle(group),
                                             (const Identity *) from_handle(identity),
                                             (const unsigned char *) bytes, (size_t) len,
                                             &handshake);
    (*env)->ReleaseByteArrayElements(env, ciphertext, bytes, JNI_ABORT);

    if (handshake == 1) {
        mls_buffer_free(out);
        return (*env)->NewByteArray(env, 0);
    }
    return take(env, out);
}

JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_MlsCore_exportIdentity(JNIEnv *env, jclass class, jlong identity) {
    return take(env, mls_identity_export((const Identity *) from_handle(identity)));
}

JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_MlsCore_openIdentity(JNIEnv *env, jclass class, jbyteArray state) {
    jsize len = (*env)->GetArrayLength(env, state);
    jbyte *bytes = (*env)->GetByteArrayElements(env, state, NULL);
    Identity *identity = mls_identity_open((const unsigned char *) bytes, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, state, bytes, JNI_ABORT);
    return to_handle(identity);
}

JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_MlsCore_groupLoad(JNIEnv *env, jclass class, jlong identity, jbyteArray id) {
    jsize len = (*env)->GetArrayLength(env, id);
    jbyte *bytes = (*env)->GetByteArrayElements(env, id, NULL);
    Group *group = mls_group_load((const Identity *) from_handle(identity),
                                  (const unsigned char *) bytes, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, id, bytes, JNI_ABORT);
    return to_handle(group);
}

JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_MlsCore_groupId(JNIEnv *env, jclass class, jlong group) {
    return take(env, mls_group_id((const Group *) from_handle(group)));
}

JNIEXPORT jint JNICALL
Java_org_telegram_messenger_MlsCore_memberCount(JNIEnv *env, jclass class, jlong group) {
    return (jint) mls_group_members((const Group *) from_handle(group));
}

JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_MlsCore_epoch(JNIEnv *env, jclass class, jlong group) {
    return (jlong) mls_group_epoch((const Group *) from_handle(group));
}

// Which conversation a ciphertext belongs to, without opening it.
//
// A message is read with the conversation it names rather than the one this
// device keeps for whoever sent it, because those are not always the same: a
// phone set up again has lost every group it was in and starts a new one, while
// the other side goes on sending in the old one until the welcome arrives.
// Picking by person there opens neither.
JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_MlsCore_messageGroupId0(JNIEnv *env, jclass class, jbyteArray ciphertext) {
    jsize len = (*env)->GetArrayLength(env, ciphertext);
    jbyte *bytes = (*env)->GetByteArrayElements(env, ciphertext, NULL);
    struct MlsBuffer out = mls_message_group_id((const unsigned char *) bytes, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, ciphertext, bytes, JNI_ABORT);
    return take(env, out);
}

// Which device a key package belongs to. A phone letting its own account's other
// phones into a conversation is handed one package per device, its own included -
// the server cannot tell which caller is which leaf - and adding that one back
// would give this device a second leaf it holds no keys for.
JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_MlsCore_keyPackageName0(JNIEnv *env, jclass class, jbyteArray keyPackage) {
    jsize len = (*env)->GetArrayLength(env, keyPackage);
    jbyte *bytes = (*env)->GetByteArrayElements(env, keyPackage, NULL);
    struct MlsBuffer out = mls_key_package_name((const unsigned char *) bytes, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, keyPackage, bytes, JNI_ABORT);
    return take(env, out);
}

// Every device of theirs at once. One welcome comes back and it lets all of
// them in; added one at a time, only the last welcome survives and which of
// their phones can join is chance.
//
// Removing somebody: one commit for the others and nothing else, because
// nobody joined. Null means nobody matched, which is ordinary - two people
// removing the same person at once, and the second is looking at a group that
// already looks the way they wanted. Whether that was a failure instead is
// what lastError answers.
JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_MlsCore_removeMembers(JNIEnv *env, jclass class, jlong group,
                                                  jlong identity, jbyteArray packed) {
    jsize len = (*env)->GetArrayLength(env, packed);
    jbyte *bytes = (*env)->GetByteArrayElements(env, packed, NULL);

    struct MlsBuffer commit = mls_group_remove_members((Group *) from_handle(group),
                                                       (const Identity *) from_handle(identity),
                                                       (const unsigned char *) bytes, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, packed, bytes, JNI_ABORT);
    return take(env, commit);
}

// Who is in the conversation, packed the same way - each name preceded by its
// length as four bytes, most significant first.
JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_MlsCore_memberNames(JNIEnv *env, jclass class, jlong group) {
    return take(env, mls_group_member_names((const Group *) from_handle(group)));
}

// A commit is left pending until the delivery service says it won its epoch.
// These two are where that answer lands: taken, or let go of so the winner can
// be applied and the change made again on top.
JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_MlsCore_acceptCommit(JNIEnv *env, jclass class, jlong group,
                                                 jlong identity) {
    return mls_group_accept_commit((Group *) from_handle(group),
                                   (const Identity *) from_handle(identity))
           ? JNI_TRUE : JNI_FALSE;
}

// A commit that arrived through the commit box. 1 - the group moved; 0 - it is
// one this device made, come back to it, and what was staged has been applied;
// -1 - it could not be applied, and lastError says why.
JNIEXPORT jint JNICALL
Java_org_telegram_messenger_MlsCore_applyCommit(JNIEnv *env, jclass class, jlong group,
                                                jlong identity, jbyteArray commit) {
    jsize len = (*env)->GetArrayLength(env, commit);
    jbyte *bytes = (*env)->GetByteArrayElements(env, commit, NULL);

    int32_t applied = mls_group_apply_commit((Group *) from_handle(group),
                                             (const Identity *) from_handle(identity),
                                             (const unsigned char *) bytes, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, commit, bytes, JNI_ABORT);
    return (jint) applied;
}

JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_MlsCore_abandonCommit(JNIEnv *env, jclass class, jlong group,
                                                  jlong identity) {
    return mls_group_abandon_commit((Group *) from_handle(group),
                                    (const Identity *) from_handle(identity))
           ? JNI_TRUE : JNI_FALSE;
}

// The packages arrive already packed - each preceded by its length as four
// bytes, most significant first - because that is one thing to keep alive
// across the boundary instead of two.
JNIEXPORT jobjectArray JNICALL
Java_org_telegram_messenger_MlsCore_addMembers(JNIEnv *env, jclass class, jlong group,
                                               jlong identity, jbyteArray packed) {
    jsize len = (*env)->GetArrayLength(env, packed);
    jbyte *bytes = (*env)->GetByteArrayElements(env, packed, NULL);

    struct MlsBuffer commit = {NULL, 0};
    struct MlsBuffer welcome = mls_group_add_members((Group *) from_handle(group),
                                                    (const Identity *) from_handle(identity),
                                                    (const unsigned char *) bytes, (size_t) len,
                                                    &commit);
    (*env)->ReleaseByteArrayElements(env, packed, bytes, JNI_ABORT);

    if (welcome.ptr == NULL) {
        mls_buffer_free(commit);
        return NULL;
    }

    jclass arrayClass = (*env)->FindClass(env, "[B");
    jobjectArray result = (*env)->NewObjectArray(env, 2, arrayClass, NULL);
    if (result == NULL) {
        mls_buffer_free(commit);
        mls_buffer_free(welcome);
        return NULL;
    }
    // Commit first, then welcome - the order addMember already uses, so the
    // caller reads both the same way whichever it called.
    (*env)->SetObjectArrayElement(env, result, 0, take(env, commit));
    (*env)->SetObjectArrayElement(env, result, 1, take(env, welcome));
    return result;
}

// The six words a person writes down, and the one-way derivation the server is
// told instead of them.
//
// The words never leave the device. What the server keeps is the derivation,
// which is enough to recognise somebody typing them and useless for signing in
// as them or reading anything.
JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_MlsCore_recoveryPhrase(JNIEnv *env, jclass class, jint words) {
    return take(env, mls_recovery_phrase((size_t) words));
}

JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_MlsCore_recoveryAuthSecret(JNIEnv *env, jclass class, jbyteArray phrase) {
    jsize len = (*env)->GetArrayLength(env, phrase);
    jbyte *bytes = (*env)->GetByteArrayElements(env, phrase, NULL);
    struct MlsBuffer out = mls_recovery_auth_secret((const unsigned char *) bytes, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, phrase, bytes, JNI_ABORT);
    return take(env, out);
}

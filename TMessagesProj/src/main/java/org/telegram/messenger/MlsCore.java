package org.telegram.messenger;

/**
 * End-to-end encryption, as Java sees it.
 *
 * The cryptography is one piece of Rust shared with the iOS client, so it cannot
 * be right in one client and wrong in the other. This class is only manners:
 * handles as longs, byte arrays across the boundary, and a failure that says why
 * rather than crashing - a crash inside a crypto library is a security event.
 *
 * The shape mirrors the core: an identity per device, a group per chat. A chat
 * between two people is a group of two, a group chat is a group of many, and a
 * person's second phone is another member of the same group. MLS does not
 * distinguish those cases, which is why it is here instead of secret chats.
 */
public final class MlsCore {

    /** Thrown instead of returning null, so a missing check cannot pass silently. */
    public static final class MlsException extends Exception {
        MlsException(String reason) {
            super(reason);
        }
    }

    private MlsCore() {
    }

    private static native String lastError();

    private static native long identityNew(byte[] name);

    private static native void identityFree(long identity);

    private static native byte[] keyPackage(long identity);

    private static native long groupCreate(long identity);

    private static native long groupJoin(long identity, byte[] welcome);

    private static native void groupFree(long group);

    private static native byte[][] addMember(long group, long identity, byte[] keyPackage);

    private static native byte[] encrypt(long group, long identity, byte[] plaintext);

    private static native byte[] decrypt(long group, long identity, byte[] ciphertext);

    private static native int memberCount(long group);

    private static native long epoch(long group);

    private static MlsException failure(String fallback) {
        String reason = lastError();
        return new MlsException(reason == null || reason.isEmpty() ? fallback : reason);
    }

    /** One device's identity: the key it signs with and the name it goes by. */
    public static final class Identity implements AutoCloseable {
        final long handle;

        /**
         * @param name what names this device - a user id and a device id, joined.
         *             Per device rather than per person: that is what makes
         *             several devices possible at all.
         */
        public Identity(byte[] name) throws MlsException {
            this.handle = identityNew(name);
            if (this.handle == 0) {
                throw failure("no identity was created");
            }
        }

        /** What somebody else needs in order to add this device to a conversation. */
        public byte[] keyPackage() throws MlsException {
            byte[] result = MlsCore.keyPackage(this.handle);
            if (result == null) {
                throw failure("no key package was built");
            }
            return result;
        }

        @Override
        public void close() {
            identityFree(this.handle);
        }
    }

    /** One conversation. */
    public static final class Group implements AutoCloseable {
        private final long handle;

        private Group(long handle) {
            this.handle = handle;
        }

        /** Starts a conversation holding only this device. */
        public static Group create(Identity identity) throws MlsException {
            long handle = groupCreate(identity.handle);
            if (handle == 0) {
                throw failure("no group was created");
            }
            return new Group(handle);
        }

        /** Joins a conversation this device was invited into. */
        public static Group join(Identity identity, byte[] welcome) throws MlsException {
            long handle = groupJoin(identity.handle, welcome);
            if (handle == 0) {
                throw failure("the invitation was refused");
            }
            return new Group(handle);
        }

        /**
         * Adds a device. Both halves have to be delivered - the commit to
         * everybody already here, the welcome to the newcomer - or the
         * conversation splits in two.
         */
        public Invitation addMember(Identity identity, byte[] keyPackage) throws MlsException {
            byte[][] pair = MlsCore.addMember(this.handle, identity.handle, keyPackage);
            if (pair == null || pair.length != 2) {
                throw failure("the member was not added");
            }
            return new Invitation(pair[0], pair[1]);
        }

        public byte[] encrypt(Identity identity, byte[] plaintext) throws MlsException {
            byte[] result = MlsCore.encrypt(this.handle, identity.handle, plaintext);
            if (result == null) {
                throw failure("nothing was encrypted");
            }
            return result;
        }

        /**
         * Reads a message, or applies a commit that moved the conversation on.
         * Returns null for the second: the caller hands everything here and does
         * not have to know which arrived.
         */
        public byte[] decrypt(Identity identity, byte[] ciphertext) throws MlsException {
            byte[] result = MlsCore.decrypt(this.handle, identity.handle, ciphertext);
            if (result == null) {
                throw failure("nothing was decrypted");
            }
            return result.length == 0 ? null : result;
        }

        /** How many devices are here. A person with two phones counts twice. */
        public int memberCount() {
            return MlsCore.memberCount(this.handle);
        }

        /**
         * The conversation's version. It moves whenever the membership or the
         * keys change, and a device left at an older one can read nothing new.
         */
        public long epoch() {
            return MlsCore.epoch(this.handle);
        }

        @Override
        public void close() {
            groupFree(this.handle);
        }
    }

    /** What adding a device produces. */
    public static final class Invitation {
        public final byte[] commit;
        public final byte[] welcome;

        Invitation(byte[] commit, byte[] welcome) {
            this.commit = commit;
            this.welcome = welcome;
        }
    }

    /**
     * Proves on the device that the whole path works: two identities, a group, a
     * message that survives the trip, and a ciphertext that does not contain the
     * plaintext. For a smoke test, not for the app.
     */
    public static String selfCheck() {
        try (Identity alice = new Identity("alice/phone".getBytes());
             Identity bob = new Identity("bob/phone".getBytes())) {

            try (Group group = Group.create(alice);
                 Group bobGroup = joinAs(bob, group, alice)) {

                byte[] secret = "the server is not supposed to read this".getBytes();
                byte[] ciphertext = group.encrypt(alice, secret);
                if (contains(ciphertext, "server".getBytes())) {
                    return "FAIL: the plaintext is visible in the ciphertext";
                }

                byte[] read = bobGroup.decrypt(bob, ciphertext);
                if (read == null) {
                    return "FAIL: that was read as a handshake, not a message";
                }
                if (!java.util.Arrays.equals(read, secret)) {
                    return "FAIL: the message did not survive";
                }
                if (bobGroup.memberCount() != 2) {
                    return "FAIL: the group holds " + bobGroup.memberCount() + " devices, expected 2";
                }
                return "ok: two devices, epoch " + group.epoch() + ", "
                        + ciphertext.length + " bytes of ciphertext";
            }
        } catch (MlsException e) {
            return "FAIL: " + e.getMessage();
        }
    }

    private static Group joinAs(Identity newcomer, Group group, Identity owner) throws MlsException {
        Invitation invitation = group.addMember(owner, newcomer.keyPackage());
        return Group.join(newcomer, invitation.welcome);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}

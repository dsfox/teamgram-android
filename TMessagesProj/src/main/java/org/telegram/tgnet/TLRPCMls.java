package org.telegram.tgnet;

/**
 * Our own methods, for end-to-end encryption on MLS.
 *
 * In a file of its own rather than inside TLRPC.java, which is seventy-seven
 * thousand lines of Telegram's schema and moves with every update. Anything of
 * ours in there would be a merge conflict on every pull, and eventually a
 * silent loss.
 *
 * The constructor ids are the CRC32 of the declarations written above each
 * class - the way TL makes them - and the server computes them from the same
 * text. tests/test_mls_constructors.py recomputes them and refuses a side that
 * disagrees, because drift here does not fail loudly: the server would answer
 * with a constructor this cannot parse, the app would show nothing, and the
 * cause would be four files away.
 */
public class TLRPCMls {

    /** mls.publishKeyPackages key_packages:Vector&lt;bytes&gt; last_resort:bytes = mls.PublishResult; */
    public static class TL_mls_publishKeyPackages extends TLObject {
        public static final int constructor = 940659472;

        public java.util.ArrayList<byte[]> key_packages = new java.util.ArrayList<>();
        /**
         * May be empty. When it is not, it is the one package handed out
         * repeatedly once the supply runs dry - the weaker path, taken so that
         * a conversation can still start.
         */
        public byte[] last_resort = new byte[0];

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return TL_mls_publishResult.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(0x1cb5c415);
            stream.writeInt32(key_packages.size());
            for (byte[] keyPackage : key_packages) {
                stream.writeByteArray(keyPackage);
            }
            stream.writeByteArray(last_resort);
        }
    }

    /** mls.publishResult added:int available:int should_refill:Bool = mls.PublishResult; */
    public static class TL_mls_publishResult extends TLObject {
        public static final int constructor = -1429473241;

        public int added;
        public int available;
        /**
         * Whether this device should make more. The server counts them; this
         * device is the only one that can make them.
         */
        public boolean should_refill;

        public static TL_mls_publishResult TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_publishResult.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.publishResult", constructor));
                }
                return null;
            }
            TL_mls_publishResult result = new TL_mls_publishResult();
            result.readParams(stream, exception);
            return result;
        }

        public void readParams(InputSerializedData stream, boolean exception) {
            added = stream.readInt32(exception);
            available = stream.readInt32(exception);
            should_refill = stream.readBool(exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(added);
            stream.writeInt32(available);
            stream.writeBool(should_refill);
        }
    }

    /** mls.claimKeyPackages user_id:long = mls.KeyPackages; */
    public static class TL_mls_claimKeyPackages extends TLObject {
        public static final int constructor = 88879177;

        public long user_id;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return TL_mls_keyPackages.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(user_id);
        }
    }

    /** mls.keyPackages packages:Vector&lt;bytes&gt; = mls.KeyPackages; */
    public static class TL_mls_keyPackages extends TLObject {
        public static final int constructor = -548140819;

        /**
         * One package per device of the person asked about. A device with
         * nothing left is missing from here rather than failing the request:
         * one silent device must not stop a conversation with the rest.
         */
        public java.util.ArrayList<byte[]> packages = new java.util.ArrayList<>();

        public static TL_mls_keyPackages TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_keyPackages.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.keyPackages", constructor));
                }
                return null;
            }
            TL_mls_keyPackages result = new TL_mls_keyPackages();
            result.readParams(stream, exception);
            return result;
        }

        public void readParams(InputSerializedData stream, boolean exception) {
            int magic = stream.readInt32(exception);
            if (magic != 0x1cb5c415) {
                if (exception) {
                    throw new RuntimeException(String.format("wrong Vector magic, got %x", magic));
                }
                return;
            }
            int count = stream.readInt32(exception);
            for (int i = 0; i < count; i++) {
                packages.add(stream.readByteArray(exception));
            }
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(0x1cb5c415);
            stream.writeInt32(packages.size());
            for (byte[] keyPackage : packages) {
                stream.writeByteArray(keyPackage);
            }
        }
    }
}

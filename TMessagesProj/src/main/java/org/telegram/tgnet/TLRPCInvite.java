package org.telegram.tgnet;

/**
 * Our own method for invitations people send themselves (#47), in a file of
 * its own for the same reason as TLRPCMls: nothing of ours inside the
 * generated schema. The ids are the CRC32 of the declarations; the gate in
 * tests/test_mls_constructors.py recomputes them.
 */
public class TLRPCInvite {

    /** invite.mint phone:string = invite.Minted; */
    public static class TL_invite_mint extends TLObject {
        public static final int constructor = -734254852;

        public String phone;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return TL_invite_minted.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(phone);
        }
    }

    /** invite.mintForChat chat_id:long phone:string = invite.Minted; */
    public static class TL_invite_mintForChat extends TLObject {
        public static final int constructor = -1620708375;

        public long chat_id;
        public String phone;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return TL_invite_minted.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(chat_id);
            stream.writeString(phone);
        }
    }

    /** invite.minted code:string expires:int = invite.Minted; */
    public static class TL_invite_minted extends TLObject {
        public static final int constructor = 730805919;

        public String code;
        /** Unix seconds: the moment the code stops working. */
        public int expires;

        public static TL_invite_minted TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_invite_minted.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in TL_invite_minted", constructor));
                }
                return null;
            }
            TL_invite_minted result = new TL_invite_minted();
            result.readParams(stream, exception);
            return result;
        }

        public void readParams(InputSerializedData stream, boolean exception) {
            code = stream.readString(exception);
            expires = stream.readInt32(exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(code);
            stream.writeInt32(expires);
        }
    }
}

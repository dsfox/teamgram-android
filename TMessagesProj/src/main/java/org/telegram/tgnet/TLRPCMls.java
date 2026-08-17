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

    /**
     * mls.setRecoverySecret secret:string = mls.Ok;
     *
     * The way back into an account, registered by the device that owns it. What
     * travels is a one-way derivation of the recovery phrase; the words are made
     * on the device and never leave it. The server used to make them and send
     * them as a message, which left every one of them in the message table in
     * plain text - and a phrase signs in without a code.
     */
    public static class TL_mls_setRecoverySecret extends TLObject {
        public static final int constructor = -369099376;

        public String secret = "";

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return TL_mls_ok.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(secret);
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

    /** mls.ok ok:Bool = mls.Ok; */
    public static class TL_mls_ok extends TLObject {
        public static final int constructor = -1518331278;

        public boolean ok;

        public static TL_mls_ok TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_ok.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.ok", constructor));
                }
                return null;
            }
            TL_mls_ok result = new TL_mls_ok();
            result.readParams(stream, exception);
            return result;
        }

        public void readParams(InputSerializedData stream, boolean exception) {
            ok = stream.readBool(exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeBool(ok);
        }
    }

    /**
     * mls.sendWelcome user_id:long welcome:bytes = mls.Ok;
     *
     * A welcome is what lets a device into a conversation somebody started with
     * it. It travels through its own method rather than as a message, so that
     * nothing about a conversation starting has to be hidden from a chat list.
     */
    public static class TL_mls_sendWelcome extends TLObject {
        public static final int constructor = -773834602;

        public long user_id;
        public byte[] welcome;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return TL_mls_ok.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(user_id);
            stream.writeByteArray(welcome);
        }
    }

    /** mls.welcome id:long from_id:long welcome:bytes = mls.Welcome; */
    public static class TL_mls_welcome extends TLObject {
        public static final int constructor = -180214709;

        public long id;
        public long from_id;
        public byte[] welcome;

        public static TL_mls_welcome TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_welcome.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.welcome", constructor));
                }
                return null;
            }
            TL_mls_welcome result = new TL_mls_welcome();
            result.readParams(stream, exception);
            return result;
        }

        public void readParams(InputSerializedData stream, boolean exception) {
            id = stream.readInt64(exception);
            from_id = stream.readInt64(exception);
            welcome = stream.readByteArray(exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt64(from_id);
            stream.writeByteArray(welcome);
        }
    }

    /** mls.welcomes welcomes:Vector&lt;mls.Welcome&gt; = mls.Welcomes; */
    public static class TL_mls_welcomes extends TLObject {
        public static final int constructor = -1921518262;

        public java.util.ArrayList<TL_mls_welcome> welcomes = new java.util.ArrayList<>();

        public static TL_mls_welcomes TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_welcomes.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.welcomes", constructor));
                }
                return null;
            }
            TL_mls_welcomes result = new TL_mls_welcomes();
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
                TL_mls_welcome item = TL_mls_welcome.TLdeserialize(stream, stream.readInt32(exception), exception);
                if (item == null) {
                    return;
                }
                welcomes.add(item);
            }
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(0x1cb5c415);
            stream.writeInt32(welcomes.size());
            for (TL_mls_welcome welcome : welcomes) {
                welcome.serializeToStream(stream);
            }
        }
    }

    /** mls.getWelcomes = mls.Welcomes; */
    public static class TL_mls_getWelcomes extends TLObject {
        public static final int constructor = -512239425;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return TL_mls_welcomes.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
        }
    }

    /**
     * mls.confirmWelcomes ids:Vector&lt;long&gt; = mls.Ok;
     *
     * Sent only after the conversation is open and saved. Confirming on receipt
     * would lose a conversation to a crash in between, and the loss would show
     * up much later as messages that will not open.
     */
    public static class TL_mls_confirmWelcomes extends TLObject {
        public static final int constructor = -1226029994;

        public java.util.ArrayList<Long> ids = new java.util.ArrayList<>();

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return TL_mls_ok.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(0x1cb5c415);
            stream.writeInt32(ids.size());
            for (Long id : ids) {
                stream.writeInt64(id);
            }
        }
    }

    /**
     * mls.content text:string entities:Vector&lt;MessageEntity&gt; = mls.Content;
     *
     * What actually gets encrypted. Not the text on its own: an entity - bold, a
     * link, a mention - is a pair of offsets into the text, and beside a
     * ciphertext those offsets point at nothing. Sent unchanged they arrive
     * pointing past the end of what the other side reads back; dropped, an
     * encrypted message silently loses its formatting.
     *
     * This never reaches the server - it is the plaintext - but it is written in
     * TL and carries a constructor like everything else, because the other
     * client has to read exactly what this one wrote.
     */
    public static class TL_mls_content extends TLObject {
        public static final int constructor = 1833308697;

        public String text = "";
        public java.util.ArrayList<TLRPC.MessageEntity> entities = new java.util.ArrayList<>();

        public static TL_mls_content TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_content.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.content", constructor));
                }
                return null;
            }
            TL_mls_content result = new TL_mls_content();
            result.readParams(stream, exception);
            return result;
        }

        public void readParams(InputSerializedData stream, boolean exception) {
            text = stream.readString(exception);
            int magic = stream.readInt32(exception);
            if (magic != 0x1cb5c415) {
                if (exception) {
                    throw new RuntimeException(String.format("wrong Vector magic, got %x", magic));
                }
                return;
            }
            int count = stream.readInt32(exception);
            for (int i = 0; i < count; i++) {
                TLRPC.MessageEntity item = TLRPC.MessageEntity.TLdeserialize(stream, stream.readInt32(exception), exception);
                if (item == null) {
                    return;
                }
                entities.add(item);
            }
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(text);
            stream.writeInt32(0x1cb5c415);
            stream.writeInt32(entities.size());
            for (TLRPC.MessageEntity entity : entities) {
                entity.serializeToStream(stream);
            }
        }
    }

    /**
     * mls.forwarded text:string entities:Vector&lt;MessageEntity&gt; from_id:long from_name:string date:int = mls.Content;
     *
     * The same thing plus who wrote it first. A forward cannot be one on the
     * wire: the server copies a message by its id, and the copy lands in a
     * conversation whose members were never able to read it. So an encrypted
     * forward is sent as a new message that says inside itself where it came
     * from, and the other side puts the "forwarded from" back.
     */
    public static class TL_mls_forwarded extends TLObject {
        public static final int constructor = 1144791349;

        public String text = "";
        public java.util.ArrayList<TLRPC.MessageEntity> entities = new java.util.ArrayList<>();
        /** Zero when the account is hidden and only a name is left. */
        public long from_id;
        public String from_name = "";
        public int date;

        public static TL_mls_forwarded TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_forwarded.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.forwarded", constructor));
                }
                return null;
            }
            TL_mls_forwarded result = new TL_mls_forwarded();
            result.readParams(stream, exception);
            return result;
        }

        public void readParams(InputSerializedData stream, boolean exception) {
            text = stream.readString(exception);
            int magic = stream.readInt32(exception);
            if (magic != 0x1cb5c415) {
                if (exception) {
                    throw new RuntimeException(String.format("wrong Vector magic, got %x", magic));
                }
                return;
            }
            int count = stream.readInt32(exception);
            for (int i = 0; i < count; i++) {
                TLRPC.MessageEntity item = TLRPC.MessageEntity.TLdeserialize(stream, stream.readInt32(exception), exception);
                if (item == null) {
                    return;
                }
                entities.add(item);
            }
            from_id = stream.readInt64(exception);
            from_name = stream.readString(exception);
            date = stream.readInt32(exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(text);
            stream.writeInt32(0x1cb5c415);
            stream.writeInt32(entities.size());
            for (TLRPC.MessageEntity entity : entities) {
                entity.serializeToStream(stream);
            }
            stream.writeInt64(from_id);
            stream.writeString(from_name);
            stream.writeInt32(date);
        }
    }

    /**
     * mls.media kind:int mime:string name:string size:long width:int height:int duration:int key:bytes iv:bytes thumb:bytes = mls.Media;
     *
     * Everything needed to show a file the server is holding as a blob of
     * random bytes: what it is, how big it is on screen, and the key that turns
     * it back into a picture. The server generates no preview for it and knows
     * no filename, because both would describe what it is not allowed to see.
     */
    public static class TL_mls_media extends TLObject {
        public static final int constructor = 859009216;

        /** 0 file, 1 picture, 2 video, 3 voice, 4 round video, 5 animation. */
        public int kind;
        public String mime = "";
        public String name = "";
        public long size;
        public int width;
        public int height;
        public int duration;
        public byte[] key = new byte[0];
        public byte[] iv = new byte[0];
        /** The blurred placeholder shown until the file has come down. */
        public byte[] thumb = new byte[0];

        public static TL_mls_media TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_media.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.media", constructor));
                }
                return null;
            }
            TL_mls_media result = new TL_mls_media();
            result.readParams(stream, exception);
            return result;
        }

        public void readParams(InputSerializedData stream, boolean exception) {
            kind = stream.readInt32(exception);
            mime = stream.readString(exception);
            name = stream.readString(exception);
            size = stream.readInt64(exception);
            width = stream.readInt32(exception);
            height = stream.readInt32(exception);
            duration = stream.readInt32(exception);
            key = stream.readByteArray(exception);
            iv = stream.readByteArray(exception);
            thumb = stream.readByteArray(exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(kind);
            stream.writeString(mime);
            stream.writeString(name);
            stream.writeInt64(size);
            stream.writeInt32(width);
            stream.writeInt32(height);
            stream.writeInt32(duration);
            stream.writeByteArray(key);
            stream.writeByteArray(iv);
            stream.writeByteArray(thumb);
        }
    }

    /** mls.forward from_id:long from_name:string date:int = mls.Forward; */
    public static class TL_mls_forward extends TLObject {
        public static final int constructor = 940936156;

        /** Zero when the account is hidden and only a name is left. */
        public long from_id;
        public String from_name = "";
        public int date;

        public static TL_mls_forward TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_forward.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.forward", constructor));
                }
                return null;
            }
            TL_mls_forward result = new TL_mls_forward();
            result.readParams(stream, exception);
            return result;
        }

        public void readParams(InputSerializedData stream, boolean exception) {
            from_id = stream.readInt64(exception);
            from_name = stream.readString(exception);
            date = stream.readInt32(exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(from_id);
            stream.writeString(from_name);
            stream.writeInt32(date);
        }
    }

    /**
     * mls.message flags:# text:string entities:Vector&lt;MessageEntity&gt; forward:flags.0?mls.Forward media:flags.1?mls.Media = mls.Content;
     *
     * What goes inside the ciphertext now that a message can be more than text:
     * the text with its formatting, where it was forwarded from, and what file
     * it carries. The two older shapes - mls.content and mls.forwarded - are
     * still read, because they are in people's chats.
     */
    public static class TL_mls_message extends TLObject {
        public static final int constructor = 995434673;

        public int flags;
        public String text = "";
        public java.util.ArrayList<TLRPC.MessageEntity> entities = new java.util.ArrayList<>();
        public TL_mls_forward forward;
        public TL_mls_media media;

        public static TL_mls_message TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_message.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.message", constructor));
                }
                return null;
            }
            TL_mls_message result = new TL_mls_message();
            result.readParams(stream, exception);
            return result;
        }

        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            text = stream.readString(exception);
            int magic = stream.readInt32(exception);
            if (magic != 0x1cb5c415) {
                if (exception) {
                    throw new RuntimeException(String.format("wrong Vector magic, got %x", magic));
                }
                return;
            }
            int count = stream.readInt32(exception);
            for (int i = 0; i < count; i++) {
                TLRPC.MessageEntity item = TLRPC.MessageEntity.TLdeserialize(stream, stream.readInt32(exception), exception);
                if (item == null) {
                    return;
                }
                entities.add(item);
            }
            if ((flags & 1) != 0) {
                forward = TL_mls_forward.TLdeserialize(stream, stream.readInt32(exception), exception);
            }
            if ((flags & 2) != 0) {
                media = TL_mls_media.TLdeserialize(stream, stream.readInt32(exception), exception);
            }
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeString(text);
            stream.writeInt32(0x1cb5c415);
            stream.writeInt32(entities.size());
            for (TLRPC.MessageEntity entity : entities) {
                entity.serializeToStream(stream);
            }
            if ((flags & 1) != 0) {
                forward.serializeToStream(stream);
            }
            if ((flags & 2) != 0) {
                media.serializeToStream(stream);
            }
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

    /**
     * mls.documentEncrypted flags:# id:long access_hash:long file_reference:bytes date:int mime_type:string size:long thumbs:flags.0?Vector&lt;PhotoSize&gt; video_thumbs:flags.1?Vector&lt;VideoSize&gt; dc_id:int attributes:Vector&lt;DocumentAttribute&gt; key:bytes iv:bytes = Document;
     *
     * A file from an encrypted conversation, as this device keeps it.
     *
     * Never on the wire. The server was sent an ordinary document full of
     * noise and that is all it will ever hold; this is the same document with
     * the key beside it, and it exists because the key has to survive being
     * written to the database.
     *
     * That was the whole fault the first time: the description was put onto a
     * plain TL_document, which serializes no key - there is nowhere in its
     * shape to put one - so it was there until the message was stored and gone
     * afterwards. The file came down and stayed ciphertext.
     *
     * Not TL_documentEncrypted, which does carry a key: that one means a
     * secret chat, and it is fetched through the secret-chat file methods,
     * which cannot find a document uploaded the ordinary way.
     */
    public static class TL_mls_documentEncrypted extends TLRPC.TL_document {
        public static final int constructor = 1021017368;

        public void readParams(InputSerializedData stream, boolean exception) {
            super.readParams(stream, exception);
            key = stream.readByteArray(exception);
            iv = stream.readByteArray(exception);
        }

        /**
         * The parent's body written out again, because the first thing it
         * writes is its own constructor and a subclass cannot ask it to write
         * a different one.
         */
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
            stream.writeByteArray(file_reference);
            stream.writeInt32(date);
            stream.writeString(mime_type);
            stream.writeInt64(size);
            if ((flags & 1) != 0) {
                Vector.serialize(stream, thumbs);
            }
            if ((flags & 2) != 0) {
                Vector.serialize(stream, video_thumbs);
            }
            stream.writeInt32(dc_id);
            Vector.serialize(stream, attributes);
            stream.writeByteArray(key);
            stream.writeByteArray(iv);
        }
    }
}

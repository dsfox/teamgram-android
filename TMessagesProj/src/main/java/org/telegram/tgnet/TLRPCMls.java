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

    /** mls.publishKeyPackages key_packages:Vector&lt;bytes&gt; last_resort:bytes name:bytes = mls.PublishResult; */
    public static class TL_mls_publishKeyPackages extends TLObject {
        public static final int constructor = -913436181;

        public java.util.ArrayList<byte[]> key_packages = new java.util.ArrayList<>();
        /**
         * May be empty. When it is not, it is the one package handed out
         * repeatedly once the supply runs dry - the weaker path, taken so that
         * a conversation can still start.
         */
        public byte[] last_resort = new byte[0];

        /**
         * The leaf name of the identity these belong to.
         *
         * A device that starts its state over leaves what it published under
         * the old identity on the server, which counts a supply by the device
         * rather than by the identity: it sees a full one, never asks for more,
         * and every invitation built from what was left behind can never be
         * opened (#136). Empty means "I cannot say", and the server then leaves
         * the supply alone.
         */
        public byte[] name = new byte[0];

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
            stream.writeByteArray(name);
        }
    }

    /** mls.publishResult added:int available:int should_refill:Bool devices:int = mls.PublishResult; */
    public static class TL_mls_publishResult extends TLObject {
        public static final int constructor = -472421573;

        public int added;
        public int available;
        /**
         * Whether this device should make more. The server counts them; this
         * device is the only one that can make them.
         */
        public boolean should_refill;
        /**
         * How many devices of this account have published anything.
         *
         * The one thing that tells this phone another phone of the same person
         * has signed in: comparing a conversation with its chat is about
         * people, so a second device of somebody already in it is invisible
         * there (#41).
         */
        public int devices;

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
            devices = stream.readInt32(exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(added);
            stream.writeInt32(available);
            stream.writeBool(should_refill);
            stream.writeInt32(devices);
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
    /**
     * mls.devicesOf users:Vector&lt;long&gt; = mls.DeviceCounts;
     *
     * How many devices each of these people has published from. It is what
     * tells a leaf whose device is gone from a leaf that is still somebody's -
     * and without it a person who replaces a phone is never let back into a
     * group, because the leaf of the device that has gone still stands for
     * them (#132).
     *
     * Counting by claiming key packages would answer the same question and
     * spend one doing it, so a group asking on its rhythm would empty
     * everybody's supply within the hour.
     */
    /**
     * mls.claimConversation peer_id:long group_id:bytes holds_everybody:Bool = mls.Conversation;
     *
     * Which conversation this chat has, settled by whoever asks first. Nothing
     * settled it before, and three people beginning a group within a minute
     * ended in two conversations that cannot read each other (#135).
     *
     * holds_everybody is the other thing to say here, and the only one that
     * replaces an answer already settled: this device is inside the
     * conversation and has just found a leaf there for every device of every
     * member of the chat. Without it the first answer stood for ever, and one
     * won by a conversation that a rebuilding device made and nobody followed
     * sends every device starting from nothing to a group with nobody in it, to
     * wait for an invitation that cannot come (#139).
     */
    public static class TL_mls_claimConversation extends TLObject {
        public static final int constructor = -936499491;

        public long peer_id;
        public byte[] group_id;
        public boolean holds_everybody;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return TL_mls_conversation.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(peer_id);
            stream.writeByteArray(group_id);
            stream.writeBool(holds_everybody);
        }
    }

    /** mls.conversation peer_id:long group_id:bytes = mls.Conversation; */
    public static class TL_mls_conversation extends TLObject {
        public static final int constructor = 622211617;

        public long peer_id;
        public byte[] group_id;

        public static TL_mls_conversation TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_conversation.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.conversation", constructor));
                }
                return null;
            }
            TL_mls_conversation result = new TL_mls_conversation();
            result.readParams(stream, exception);
            return result;
        }

        public void readParams(InputSerializedData stream, boolean exception) {
            peer_id = stream.readInt64(exception);
            group_id = stream.readByteArray(exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(peer_id);
            stream.writeByteArray(group_id);
        }
    }

    public static class TL_mls_devicesOf extends TLObject {
        public static final int constructor = -657797125;

        public java.util.ArrayList<Long> users = new java.util.ArrayList<>();

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return TL_mls_deviceCounts.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(0x1cb5c415);
            stream.writeInt32(users.size());
            for (Long id : users) {
                stream.writeInt64(id);
            }
        }
    }

    /** mls.deviceCounts counts:Vector&lt;int&gt; names:Vector&lt;bytes&gt; = mls.DeviceCounts; */
    public static class TL_mls_deviceCounts extends TLObject {
        public static final int constructor = 1890672928;

        /**
         * One count per person asked about, in the order they were asked. Zero
         * means the server could not say, and nothing is concluded from it - it
         * already means "nobody has asked yet" everywhere this is read.
         */
        public java.util.ArrayList<Integer> counts = new java.util.ArrayList<>();

        /**
         * The leaf name of every one of those devices, all of them in one list:
         * the counts say where to cut it. One entry per counted device, so the
         * cut is always right, and empty for a device that published before key
         * packages said which identity they belong to (#136).
         *
         * In the same answer as the counts on purpose. Taking a leaf out asks
         * two questions - whether a device is missing, which the count answers,
         * and which leaf is it, which these answer - and while they came from
         * two calls they could disagree (#139).
         */
        public java.util.ArrayList<byte[]> names = new java.util.ArrayList<>();

        public static TL_mls_deviceCounts TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_deviceCounts.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.deviceCounts", constructor));
                }
                return null;
            }
            TL_mls_deviceCounts result = new TL_mls_deviceCounts();
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
                counts.add(stream.readInt32(exception));
            }

            magic = stream.readInt32(exception);
            if (magic != 0x1cb5c415) {
                if (exception) {
                    throw new RuntimeException(String.format("wrong Vector magic, got %x", magic));
                }
                return;
            }
            int named = stream.readInt32(exception);
            for (int i = 0; i < named; i++) {
                names.add(stream.readByteArray(exception));
            }
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(0x1cb5c415);
            stream.writeInt32(counts.size());
            for (Integer n : counts) {
                stream.writeInt32(n);
            }
            stream.writeInt32(0x1cb5c415);
            stream.writeInt32(names.size());
            for (byte[] name : names) {
                stream.writeByteArray(name);
            }
        }

        /**
         * The names belonging to the person at that place in the answer, or
         * null when the answer cannot be cut there.
         *
         * Null rather than an empty list, because the two mean opposite things:
         * a person with no devices is a real answer and a list that does not
         * add up is one nothing may be concluded from. Everything that removes
         * a leaf goes through here, so a short or ragged answer stops it rather
         * than shifting one person's devices onto the next.
         */
        public java.util.List<byte[]> namesOf(int who) {
            if (who < 0 || who >= counts.size()) {
                return null;
            }
            int at = 0;
            for (int i = 0; i < who; i++) {
                at += counts.get(i);
            }
            int mine = counts.get(who);
            if (at < 0 || mine < 0 || at + mine > names.size()) {
                return null;
            }
            return names.subList(at, at + mine);
        }
    }

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
        public static final int constructor = 2042714623;

        public long user_id;
        /**
         * Which chat the invitation is for, as a dialog id - negative for a
         * group.
         *
         * Without it a welcome says only who sent it, and the device joining
         * files the conversation under that person: a group ends up recorded as
         * the conversation with whoever invited them, and a private message to
         * that person is then written with the group's keys (#115).
         */
        public long peer_id;
        public byte[] welcome;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return TL_mls_ok.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(user_id);
            stream.writeInt64(peer_id);
            stream.writeByteArray(welcome);
        }
    }

    /** mls.welcome id:long from_id:long peer_id:long welcome:bytes = mls.Welcome; */
    public static class TL_mls_welcome extends TLObject {
        public static final int constructor = 215890102;

        public long id;
        public long from_id;
        /** The chat this invitation is for; zero for one written before
         *  invitations carried it, and then the sender is all there is. */
        public long peer_id;
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
            peer_id = stream.readInt64(exception);
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

    /**
     * mls.photoEncrypted flags:# id:long access_hash:long file_reference:bytes date:int sizes:Vector&lt;PhotoSize&gt; dc_id:int document:Document = Photo;
     *
     * A picture from an encrypted conversation, as this device keeps it.
     *
     * Never on the wire: what the server holds is a document full of noise,
     * and this is that document dressed as a photograph so the client draws it
     * the way it draws every other picture - in a bubble rather than as a row
     * with a file name on it, which is what a document gets.
     *
     * The document travels inside it because that is how the bytes are
     * fetched. A photograph is fetched by a photo id the server would have to
     * know, and it does not: it was never given a photograph.
     */
    public static class TL_mls_photoEncrypted extends TLRPC.TL_photo {
        public static final int constructor = 1056613626;

        /** The blob the server is holding, with the key that opens it. */
        public TLRPC.Document document;

        public void readParams(InputSerializedData stream, boolean exception) {
            super.readParams(stream, exception);
            document = TLRPC.Document.TLdeserialize(stream, stream.readInt32(exception), exception);
        }

        /**
         * The parent's body again, because the first thing it writes is its own
         * constructor and a subclass cannot ask it to write a different one.
         */
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
            stream.writeByteArray(file_reference);
            stream.writeInt32(date);
            Vector.serialize(stream, sizes);
            if ((flags & 1) != 0) {
                Vector.serialize(stream, video_sizes);
            }
            stream.writeInt32(dc_id);
            document.serializeToStream(stream);
        }
    }

    // ------------------------------------------------------------------
    // Moving a group to its next epoch (#40)
    //
    // A commit is what a membership change is: somebody added, somebody
    // removed. It travels through its own methods for the same reason a
    // welcome does - handshake traffic never touches the message pipeline, so
    // nothing has to be hidden from a chat list.
    // ------------------------------------------------------------------

    /** mls.commitResult accepted:Bool epoch:long = mls.CommitResult; */
    public static class TL_mls_commitResult extends TLObject {
        public static final int constructor = 191372459;

        public boolean accepted;
        /** Where the conversation really is. Present on refusal too, which is
         *  the whole point: it tells the loser of a race how far behind it is
         *  without another round trip. */
        public long epoch;

        public static TL_mls_commitResult TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_commitResult.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.commitResult", constructor));
                }
                return null;
            }
            TL_mls_commitResult result = new TL_mls_commitResult();
            result.readParams(stream, exception);
            return result;
        }

        public void readParams(InputSerializedData stream, boolean exception) {
            accepted = stream.readBool(exception);
            epoch = stream.readInt64(exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeBool(accepted);
            stream.writeInt64(epoch);
        }
    }

    /** mls.sendCommit group_id:bytes epoch:long members:Vector&lt;long&gt; commit:bytes = mls.CommitResult; */
    public static class TL_mls_sendCommit extends TLObject {
        public static final int constructor = -945155929;

        public byte[] group_id;
        public long epoch;
        /** Where to leave it. The server does not know who is in a group and
         *  must not - it is told, not asked. */
        public java.util.ArrayList<Long> members = new java.util.ArrayList<>();
        public byte[] commit;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return TL_mls_commitResult.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeByteArray(group_id);
            stream.writeInt64(epoch);
            stream.writeInt32(0x1cb5c415);
            stream.writeInt32(members.size());
            for (int i = 0; i < members.size(); i++) {
                stream.writeInt64(members.get(i));
            }
            stream.writeByteArray(commit);
        }
    }

    /** mls.commit id:long from_id:long group_id:bytes epoch:long commit:bytes = mls.Commit; */
    public static class TL_mls_commit extends TLObject {
        public static final int constructor = -130530128;

        public long id;
        public long from_id;
        public byte[] group_id;
        public long epoch;
        public byte[] commit;

        public static TL_mls_commit TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_commit.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.commit", constructor));
                }
                return null;
            }
            TL_mls_commit result = new TL_mls_commit();
            result.readParams(stream, exception);
            return result;
        }

        public void readParams(InputSerializedData stream, boolean exception) {
            id = stream.readInt64(exception);
            from_id = stream.readInt64(exception);
            group_id = stream.readByteArray(exception);
            epoch = stream.readInt64(exception);
            commit = stream.readByteArray(exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeInt64(from_id);
            stream.writeByteArray(group_id);
            stream.writeInt64(epoch);
            stream.writeByteArray(commit);
        }
    }

    /** mls.commits commits:Vector&lt;mls.Commit&gt; = mls.Commits; */
    public static class TL_mls_commits extends TLObject {
        public static final int constructor = -902742102;

        public java.util.ArrayList<TL_mls_commit> commits = new java.util.ArrayList<>();

        public static TL_mls_commits TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            if (TL_mls_commits.constructor != constructor) {
                if (exception) {
                    throw new RuntimeException(String.format("can't parse magic %x in mls.commits", constructor));
                }
                return null;
            }
            TL_mls_commits result = new TL_mls_commits();
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
                TL_mls_commit item = TL_mls_commit.TLdeserialize(stream, stream.readInt32(exception), exception);
                if (item == null) {
                    return;
                }
                commits.add(item);
            }
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(0x1cb5c415);
            stream.writeInt32(commits.size());
            for (int i = 0; i < commits.size(); i++) {
                commits.get(i).serializeToStream(stream);
            }
        }
    }

    /** mls.getCommits = mls.Commits; */
    public static class TL_mls_getCommits extends TLObject {
        public static final int constructor = 1356576713;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return TL_mls_commits.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
        }
    }

    /** mls.confirmCommits ids:Vector&lt;long&gt; = mls.Ok; */
    public static class TL_mls_confirmCommits extends TLObject {
        public static final int constructor = 96655983;

        public java.util.ArrayList<Long> ids = new java.util.ArrayList<>();

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return TL_mls_ok.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(0x1cb5c415);
            stream.writeInt32(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                stream.writeInt64(ids.get(i));
            }
        }
    }
}

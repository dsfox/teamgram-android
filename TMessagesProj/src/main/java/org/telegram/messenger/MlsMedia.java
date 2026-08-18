package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLRPCMls;

import java.util.ArrayList;

/**
 * A file sent into an encrypted conversation, and one arriving from it.
 *
 * The counterpart of MlsMediaUpload.swift and MlsMediaIncoming.swift, and the
 * same idea: the bytes go up encrypted and the server keeps them as a document
 * with no type, no name and no dimensions - nothing it could preview, index or
 * describe. Everything needed to make sense of them again travels inside the
 * message, where the server cannot read it either.
 *
 * The encryption is not new. FileUploadOperation has been encrypting uploads
 * for secret chats for years and FileLoadOperation has been decrypting them.
 * What is new is that the result is sent as an ordinary document rather than
 * through the secret-chat methods, so nothing on the server has to change.
 */
public final class MlsMedia {

    /** What the thing is, since its mime type is not travelling with it.
     *  The numbers are the wire, and they match the other client. */
    public static final int KIND_FILE = 0;
    public static final int KIND_IMAGE = 1;
    public static final int KIND_VIDEO = 2;
    public static final int KIND_VOICE = 3;
    public static final int KIND_ROUND_VIDEO = 4;
    public static final int KIND_ANIMATION = 5;

    private MlsMedia() {
    }

    // ----------------------------------------------------------------------
    // Going out
    // ----------------------------------------------------------------------

    /**
     * Reads what is about to be sent and writes down what the other side will
     * need to make sense of it.
     *
     * Called once the upload is finished, because that is when the key exists:
     * FileUploadOperation makes it, uses it on every part, and hands it back.
     *
     * The size is the file's own, not the server's. What the server holds is
     * the plaintext padded up to the cipher's block size, and a client told
     * that number waits for bytes that are never coming - which is a download
     * that never finishes rather than an error anybody would see.
     */
    public static TLRPCMls.TL_mls_media describe(TLRPC.Message message, byte[] key, byte[] iv,
                                                 long decryptedSize) {
        return describe(message, key, iv, decryptedSize, null);
    }

    /**
     * The same, with the file to make the placeholder from.
     *
     * The path is the one that was just uploaded, so the picture is read as it
     * is going out rather than looked for afterwards.
     */
    public static TLRPCMls.TL_mls_media describe(TLRPC.Message message, byte[] key, byte[] iv,
                                                 long decryptedSize, String path) {
        if (message == null || key == null || iv == null || decryptedSize <= 0) {
            return null;
        }
        TLRPCMls.TL_mls_media media = new TLRPCMls.TL_mls_media();
        media.key = key;
        media.iv = iv;
        media.size = decryptedSize;
        media.kind = KIND_FILE;
        media.mime = "application/octet-stream";
        media.name = "file";
        media.thumb = new byte[0];

        TLRPC.MessageMedia carried = MessageObject.getMedia(message);
        if (carried instanceof TLRPC.TL_messageMediaPhoto) {
            TLRPC.Photo photo = carried.photo;
            media.kind = KIND_IMAGE;
            media.mime = "image/jpeg";
            media.name = "photo.jpg";
            TLRPC.PhotoSize largest = photo == null ? null
                    : FileLoader.getClosestPhotoSizeWithSize(photo.sizes, Integer.MAX_VALUE);
            if (largest != null) {
                media.width = largest.w;
                media.height = largest.h;
            }
            byte[] blurred = miniThumbnail(path);
            if (blurred != null) {
                media.thumb = blurred;
            }
            return media;
        }

        if (!(carried instanceof TLRPC.TL_messageMediaDocument) || carried.document == null) {
            // A contact, a location, a poll: nothing of ours to carry. Those
            // hold no bytes, and what they do leak is a separate question.
            return null;
        }

        TLRPC.Document document = carried.document;
        media.mime = document.mime_type == null ? "application/octet-stream" : document.mime_type;
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                media.name = attribute.file_name;
            } else if (attribute instanceof TLRPC.TL_documentAttributeImageSize) {
                media.width = attribute.w;
                media.height = attribute.h;
                if (media.kind == KIND_FILE) {
                    media.kind = KIND_IMAGE;
                }
            } else if (attribute instanceof TLRPC.TL_documentAttributeAnimated) {
                media.kind = KIND_ANIMATION;
            } else if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                media.kind = attribute.round_message ? KIND_ROUND_VIDEO : KIND_VIDEO;
                media.width = attribute.w;
                media.height = attribute.h;
                media.duration = (int) attribute.duration;
            } else if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                if (attribute.voice) {
                    media.kind = KIND_VOICE;
                }
                media.duration = (int) attribute.duration;
            }
        }
        return media;
    }

    /**
     * The blurred placeholder a picture travels with, in the shape the other
     * client already knows how to read.
     *
     * The server makes no preview of an encrypted file and could not: it is
     * holding noise. So this is made here, and it is small enough - a couple of
     * hundred bytes - to go inside the message rather than as a file of its own.
     *
     * The format is Telegram's stripped thumbnail, and it is a JPEG with its
     * head cut off: everything up to the scan is the same for every one of
     * them, so it is not sent, and the reader puts it back. Which means the
     * bytes here have to be produced with exactly the tables in that template -
     * ImageLoader.getStrippedPhotoBitmap glues them onto whatever arrives and
     * does not check. Quality 20 is what the template's quantisation table is,
     * and the result is compared against it byte for byte rather than trusted:
     * a thumbnail encoded with different tables is not a worse picture, it is
     * the wrong colours and blocks.
     *
     * Null when it cannot be made, and then the picture simply travels without
     * one, which is what happened before this existed.
     *
     * And on this platform it cannot yet be made at all, which was worth
     * measuring rather than assuming. Bitmap.compress writes its own optimised
     * Huffman tables: a 40-pixel thumbnail comes out 951 bytes, of which 494
     * are a colour profile, leaving less table and scan than the template's
     * tables alone occupy. The two cannot be made to agree by choosing a
     * quality - the tables are a different set, not a scaled one. Making one
     * here means writing a JPEG encoder with the template's tables fixed, or
     * agreeing a second shape between the two clients: a plain small JPEG
     * would do, and the two are told apart by their first byte, since a
     * stripped thumbnail begins with 0x01 and a JPEG with 0xFF. See #79.
     *
     * This is left in place because it is self-checking: it compares what it
     * made against the template and sends nothing rather than something wrong,
     * so a platform whose encoder does agree would simply start working.
     */
    public static byte[] miniThumbnail(String path) {
        if (path == null || path.isEmpty()) {
            return no("there is no file to read");
        }
        try {
            android.graphics.BitmapFactory.Options options =
                    new android.graphics.BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return no("the file is not a picture: " + path);
            }
            // Read down first rather than loading a photograph to shrink it.
            options.inJustDecodeBounds = false;
            options.inSampleSize = Math.max(1, Math.min(options.outWidth, options.outHeight) / 40);
            android.graphics.Bitmap full = android.graphics.BitmapFactory.decodeFile(path, options);
            if (full == null) {
                return no("the picture would not decode: " + path);
            }

            // Both sides fit in one byte each, which is the format's own limit.
            float side = Math.max(full.getWidth(), full.getHeight());
            float scale = side > 40 ? 40f / side : 1f;
            android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(full,
                    Math.max(1, Math.round(full.getWidth() * scale)),
                    Math.max(1, Math.round(full.getHeight() * scale)), true);
            if (small != full) {
                full.recycle();
            }

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 20, out);
            small.recycle();
            byte[] jpeg = out.toByteArray();

            byte[] template = Bitmaps.header;
            // Compared from the quantisation table rather than from the first
            // byte, because this encoder puts a colour profile in between: the
            // template goes JFIF then straight to FF DB, and what comes out
            // here goes JFIF, FF E2 ICC_PROFILE, then FF DB. Everything the
            // reader cares about is the same; it starts at a different place.
            final int tables = 20;                     // where FF DB is in the template
            int start = markerAt(jpeg, 0xDB);
            if (start < 0) {
                return no("the encoder wrote no quantisation table");
            }
            if (jpeg.length <= start + (template.length - tables) + Bitmaps.footer.length) {
                return no("the encoded thumbnail is only " + jpeg.length + " bytes, tables at " + start);
            }
            for (int k = 0; k + tables < template.length; k++) {
                int i = tables + k;
                // 164 and 166 are where the picture's own size sits, and they
                // are the two bytes that travel.
                if (i != 164 && i != 166 && jpeg[start + k] != template[i]) {
                    StringBuilder made = new StringBuilder();
                    StringBuilder wanted = new StringBuilder();
                    for (int j = Math.max(0, start + k - 4); j < Math.min(jpeg.length, start + k + 8); j++) {
                        made.append(String.format("%02x ", jpeg[j]));
                    }
                    for (int j = Math.max(0, i - 4); j < Math.min(template.length, i + 8); j++) {
                        wanted.append(String.format("%02x ", template[j]));
                    }
                    FileLog.e("mls: this device encodes thumbnails differently at byte " + i
                            + ", so none is sent. made: " + made + "| wanted: " + wanted);
                    return null;
                }
            }

            int after = start + (template.length - tables);
            int carried = jpeg.length - after - Bitmaps.footer.length;
            byte[] stripped = new byte[3 + carried];
            stripped[0] = 1;
            // Taken from what was actually encoded rather than from what was
            // asked for, so the two bytes are whatever the reader will glue
            // back into the same places.
            stripped[1] = jpeg[start + (164 - tables)];
            stripped[2] = jpeg[start + (166 - tables)];
            System.arraycopy(jpeg, after, stripped, 3, carried);
            return stripped;
        } catch (Throwable e) {
            // A picture that cannot be read is not a reason to fail the send.
            FileLog.e("mls: cannot make a thumbnail: " + e.getMessage());
            return null;
        }
    }

    /** Says why there is no placeholder, once, where it can be read later. */
    private static byte[] no(String why) {
        // Not an error. On this platform it is the ordinary outcome - see the
        // note on miniThumbnail - and a picture without a placeholder is a
        // picture, so this is a note rather than a complaint.
        FileLog.d("mls: no placeholder - " + why);
        return null;
    }

    /** Where a JPEG marker starts, skipping the segments before it. */
    private static int markerAt(byte[] jpeg, int marker) {
        for (int i = 2; i + 1 < jpeg.length; ) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                return -1;
            }
            int kind = jpeg[i + 1] & 0xFF;
            if (kind == marker) {
                return i;
            }
            if (i + 3 >= jpeg.length) {
                return -1;
            }
            i += 2 + (((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF));
        }
        return -1;
    }

    /**
     * The same parts that were just uploaded, named as an ordinary file.
     *
     * The encrypted upload answers with an InputEncryptedFile because that is
     * what the secret-chat methods take. It describes the same uploaded file as
     * an InputFile does - the same id, the same number of parts - so the bytes
     * can be sent as a plain document without being uploaded twice.
     */
    public static TLRPC.InputFile asPlainFile(TLRPC.InputEncryptedFile uploaded) {
        if (!(uploaded instanceof TLRPC.TL_inputEncryptedFileUploaded)) {
            return null;
        }
        TLRPC.TL_inputFile file = new TLRPC.TL_inputFile();
        file.id = uploaded.id;
        file.parts = uploaded.parts;
        file.md5_checksum = "";
        file.name = "file";
        return file;
    }

    /**
     * What to send instead of the picture or the file that was chosen.
     *
     * No mime type, no name, no dimensions, no thumbnail: every one of those
     * describes what the server is not allowed to see. What is left is a
     * document full of noise, which is all it needs to store it.
     */
    public static TLRPC.TL_inputMediaUploadedDocument asBlob(TLRPC.InputFile file) {
        TLRPC.TL_inputMediaUploadedDocument blob = new TLRPC.TL_inputMediaUploadedDocument();
        blob.file = file;
        blob.mime_type = "application/octet-stream";
        TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
        name.file_name = "file";
        blob.attributes.add(name);
        return blob;
    }

    // ----------------------------------------------------------------------
    // Coming in
    // ----------------------------------------------------------------------

    /**
     * Puts the description back on the blob that arrived.
     *
     * The document keeps its place - its id, its access hash, its datacenter -
     * because that is how it is fetched. What changes is everything describing
     * it, which until now was a lie the server was told on purpose.
     *
     * The key goes onto the document as well. FileLoadOperation decrypts any
     * document carrying one, the same way it has always decrypted a file from a
     * secret chat, because the bytes were encrypted the same way going up.
     */
    public static boolean attach(TLRPC.Message message, TLRPCMls.TL_mls_media descriptor) {
        if (message == null || descriptor == null) {
            return false;
        }
        TLRPC.MessageMedia carried = MessageObject.getMedia(message);
        if (!(carried instanceof TLRPC.TL_messageMediaDocument) || carried.document == null) {
            // The two halves disagree: a message describing a file, arriving
            // without one. Showing the blob is better than showing a picture
            // that cannot exist.
            return false;
        }

        // Kept as our own kind of document rather than a plain one. A plain
        // TL_document has nowhere in its shape to write a key, so the key was
        // there until the message was stored and gone by the time the file was
        // fetched - and what came down stayed ciphertext.
        TLRPCMls.TL_mls_documentEncrypted document = new TLRPCMls.TL_mls_documentEncrypted();
        TLRPC.Document blob = carried.document;
        document.id = blob.id;
        document.access_hash = blob.access_hash;
        document.file_reference = blob.file_reference == null ? new byte[0] : blob.file_reference;
        document.date = blob.date;
        document.dc_id = blob.dc_id;
        document.flags = blob.flags;
        carried.document = document;

        document.mime_type = descriptor.mime == null || descriptor.mime.isEmpty()
                ? "application/octet-stream" : descriptor.mime;
        // The file's own size, not the padded one the server is holding. It is
        // what the download is measured against and what a person is shown.
        document.size = descriptor.size;
        document.key = descriptor.key;
        document.iv = descriptor.iv;

        document.attributes = new ArrayList<>();
        TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
        name.file_name = descriptor.name == null || descriptor.name.isEmpty()
                ? "file" : descriptor.name;
        document.attributes.add(name);

        switch (descriptor.kind) {
            case KIND_VIDEO:
            case KIND_ROUND_VIDEO: {
                TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
                video.duration = descriptor.duration;
                video.w = descriptor.width;
                video.h = descriptor.height;
                video.round_message = descriptor.kind == KIND_ROUND_VIDEO;
                // Streaming is exactly what this cannot do: the file is one
                // piece of ciphertext and there is nothing to play until all of
                // it has arrived.
                video.supports_streaming = false;
                document.attributes.add(video);
                break;
            }
            case KIND_VOICE: {
                TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
                audio.voice = true;
                audio.duration = descriptor.duration;
                audio.flags |= 1024;
                document.attributes.add(audio);
                break;
            }
            case KIND_ANIMATION: {
                document.attributes.add(new TLRPC.TL_documentAttributeAnimated());
                addImageSize(document, descriptor);
                break;
            }
            default:
                addImageSize(document, descriptor);
                break;
        }

        // The blurred placeholder that came inside the message. The server made
        // no preview of this file and could not have: it is holding noise.
        document.thumbs = new ArrayList<>();
        if (descriptor.thumb != null && descriptor.thumb.length > 0) {
            TLRPC.TL_photoStrippedSize thumb = new TLRPC.TL_photoStrippedSize();
            thumb.type = "i";
            thumb.bytes = descriptor.thumb;
            document.thumbs.add(thumb);
            document.flags |= 1;
        }
        return true;
    }

    private static void addImageSize(TLRPC.Document document, TLRPCMls.TL_mls_media descriptor) {
        if (descriptor.width <= 0 || descriptor.height <= 0) {
            return;
        }
        TLRPC.TL_documentAttributeImageSize size = new TLRPC.TL_documentAttributeImageSize();
        size.w = descriptor.width;
        size.h = descriptor.height;
        document.attributes.add(size);
    }
}

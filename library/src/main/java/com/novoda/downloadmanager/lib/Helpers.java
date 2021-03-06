/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.novoda.downloadmanager.lib;

import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.webkit.MimeTypeMap;

import com.novoda.downloadmanager.lib.logger.LLog;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Some helper functions for the download manager
 */
class Helpers {
    public static Random sRandom = new Random(SystemClock.uptimeMillis());

    /**
     * Regex used to parse content-disposition headers
     */
    private static final Pattern CONTENT_DISPOSITION_PATTERN =
            Pattern.compile("attachment;\\s*filename\\s*=\\s*\"([^\"]*)\"");

    private static final Object UNIQUE_LOCK = new Object();

    private Helpers() {
    }

    /*
     * Parse the Content-Disposition HTTP Header. The format of the header
     * is defined here: http://www.w3.org/Protocols/rfc2616/rfc2616-sec19.html
     * This header provides a filename for content that is going to be
     * downloaded to the file system. We only support the attachment type.
     */
    private static String parseContentDisposition(String contentDisposition) {
        try {
            Matcher m = CONTENT_DISPOSITION_PATTERN.matcher(contentDisposition);
            if (m.find()) {
                return m.group(1);
            }
        } catch (IllegalStateException ex) {
            // This function is defined as returning null when it can't parse the header
        }
        return null;
    }

    /**
     * Creates a filename (where the file should be saved) from info about a download.
     */
    static String generateSaveFile(String url, String hint, String contentDisposition,
                                   String contentLocation, String mimeType, int destination, long contentLength,
                                   StorageManager storageManager) throws StopRequestException {
        if (contentLength < 0) {
            contentLength = 0;
        }
        String path;
        File base = null;
        if (destination == DownloadsDestination.DESTINATION_FILE_URI) {
            path = Uri.parse(hint).getPath();
        } else {
            base = storageManager.locateDestinationDirectory(mimeType, destination, contentLength);
            path = chooseFilename(url, hint, contentDisposition, contentLocation);
        }
        storageManager.verifySpace(destination, path, contentLength);
        if (DownloadDrmHelper.isDrmConvertNeeded(mimeType)) {
            path = DownloadDrmHelper.modifyDrmFwLockFileExtension(path);
        }
        path = getFullPath(path, mimeType, destination, base);
        return path;
    }

    static String getFullPath(String filename, String mimeType, int destination, File base)
            throws StopRequestException {
        String extension = null;
        int dotIndex = filename.lastIndexOf('.');
        boolean missingExtension = dotIndex < 0 || dotIndex < filename.lastIndexOf('/');
        if (destination == DownloadsDestination.DESTINATION_FILE_URI) {
            // Destination is explicitly set - do not change the extension
            if (missingExtension) {
                extension = "";
            } else {
                extension = filename.substring(dotIndex);
                filename = filename.substring(0, dotIndex);
            }
        } else {
            // Split filename between base and extension
            // Add an extension if filename does not have one
            if (missingExtension) {
                extension = chooseExtensionFromMimeType(mimeType, true);
            } else {
                extension = chooseExtensionFromFilename(mimeType, filename, dotIndex);
                filename = filename.substring(0, dotIndex);
            }
        }

        boolean recoveryDir = Constants.RECOVERY_DIRECTORY.equalsIgnoreCase(filename + extension);

        if (base != null) {
            filename = base.getPath() + File.separator + filename;
        }

        LLog.v("target file: " + filename + extension);

        synchronized (UNIQUE_LOCK) {
            final String path = chooseUniqueFilenameLocked(
                    destination, filename, extension, recoveryDir);

            // Claim this filename inside lock to prevent other threads from
            // clobbering us. We're not paranoid enough to use O_EXCL.
            try {
                new File(path).createNewFile();
            } catch (IOException e) {
                throw new StopRequestException(DownloadStatus.FILE_ERROR, "Failed to create target file " + path, e);
            }
            return path;
        }
    }

    private static String chooseFilename(String url, String hint, String contentDisposition,
                                         String contentLocation) {
        String filename = null;

        // First, try to use the hint from the application, if there's one
        if (hint != null && !hint.endsWith("/")) {
            LLog.v("getting filename from hint");
            int index = hint.lastIndexOf('/') + 1;
            if (index > 0) {
                filename = hint.substring(index);
            } else {
                filename = hint;
            }
        }

        // If we couldn't do anything with the hint, move toward the content disposition
        if (filename == null && contentDisposition != null) {
            filename = parseContentDisposition(contentDisposition);
            if (filename != null) {
                LLog.v("getting filename from content-disposition");
                int index = filename.lastIndexOf('/') + 1;
                if (index > 0) {
                    filename = filename.substring(index);
                }
            }
        }

        // If we still have nothing at this point, try the content location
        if (filename == null && contentLocation != null) {
            String decodedContentLocation = Uri.decode(contentLocation);
            if (decodedContentLocation != null
                    && !decodedContentLocation.endsWith("/")
                    && decodedContentLocation.indexOf('?') < 0) {
                LLog.v("getting filename from content-location");
                int index = decodedContentLocation.lastIndexOf('/') + 1;
                if (index > 0) {
                    filename = decodedContentLocation.substring(index);
                } else {
                    filename = decodedContentLocation;
                }
            }
        }

        // If all the other http-related approaches failed, use the plain uri
        if (filename == null) {
            String decodedUrl = Uri.decode(url);
            if (decodedUrl != null
                    && !decodedUrl.endsWith("/") && decodedUrl.indexOf('?') < 0) {
                int index = decodedUrl.lastIndexOf('/') + 1;
                if (index > 0) {
                    LLog.v("getting filename from uri");
                    filename = decodedUrl.substring(index);
                }
            }
        }

        // Finally, if couldn't get filename from URI, get a generic filename
        if (filename == null) {
            LLog.v("using default filename");
            filename = Constants.DEFAULT_DL_FILENAME;
        }

        // The VFAT file system is assumed as target for downloads.
        // Replace invalid characters according to the specifications of VFAT.
        filename = replaceInvalidVfatCharacters(filename);

        return filename;
    }

    private static String chooseExtensionFromMimeType(String mimeType, boolean useDefaults) {
        String extension = null;
        if (mimeType != null) {
            extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (extension != null) {
                LLog.v("adding extension from type");
                extension = "." + extension;
            } else {
                LLog.v("couldn't find extension for " + mimeType);
            }
        }
        if (extension == null) {
            if (mimeType != null && mimeType.toLowerCase(Locale.US).startsWith("text/")) {
                if (mimeType.equalsIgnoreCase("text/html")) {
                    LLog.v("adding default html extension");
                    extension = Constants.DEFAULT_DL_HTML_EXTENSION;
                } else if (useDefaults) {
                    LLog.v("adding default text extension");
                    extension = Constants.DEFAULT_DL_TEXT_EXTENSION;
                }
            } else if (useDefaults) {
                LLog.v("adding default binary extension");
                extension = Constants.DEFAULT_DL_BINARY_EXTENSION;
            }
        }
        return extension;
    }

    private static String chooseExtensionFromFilename(String mimeType,
                                                      String filename, int lastDotIndex) {
        String extension = null;
        if (mimeType != null) {
            // Compare the last segment of the extension against the mime type.
            // If there's a mismatch, discard the entire extension.
            String typeFromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    filename.substring(lastDotIndex + 1));
            if (typeFromExt == null || !typeFromExt.equalsIgnoreCase(mimeType)) {
                extension = chooseExtensionFromMimeType(mimeType, false);
                if (extension != null) {
                    LLog.v("substituting extension from type");
                } else {
                    LLog.v("couldn't find extension for " + mimeType);
                }
            }
        }
        if (extension == null) {
            LLog.v("keeping extension");
            extension = filename.substring(lastDotIndex);
        }
        return extension;
    }

    private static String chooseUniqueFilenameLocked(int destination, String filename,
                                                     String extension, boolean recoveryDir) throws StopRequestException {
        String fullFilename = filename + extension;
        if (!new File(fullFilename).exists()
                && (!recoveryDir ||
                (destination != DownloadsDestination.DESTINATION_CACHE_PARTITION &&
                        destination != DownloadsDestination.DESTINATION_SYSTEMCACHE_PARTITION &&
                        destination != DownloadsDestination.DESTINATION_CACHE_PARTITION_PURGEABLE &&
                        destination != DownloadsDestination.DESTINATION_CACHE_PARTITION_NOROAMING))) {
            return fullFilename;
        }
        filename = filename + Constants.FILENAME_SEQUENCE_SEPARATOR;
        /*
        * This number is used to generate partially randomized filenames to avoid
        * collisions.
        * It starts at 1.
        * The next 9 iterations increment it by 1 at a time (up to 10).
        * The next 9 iterations increment it by 1 to 10 (random) at a time.
        * The next 9 iterations increment it by 1 to 100 (random) at a time.
        * ... Up to the point where it increases by 100000000 at a time.
        * (the maximum value that can be reached is 1000000000)
        * As soon as a number is reached that generates a filename that doesn't exist,
        *     that filename is used.
        * If the filename coming in is [base].[ext], the generated filenames are
        *     [base]-[sequence].[ext].
        */
        int sequence = 1;
        for (int magnitude = 1; magnitude < 1000000000; magnitude *= 10) {
            for (int iteration = 0; iteration < 9; ++iteration) {
                fullFilename = filename + sequence + extension;
                if (!new File(fullFilename).exists()) {
                    return fullFilename;
                }
                LLog.v("file with sequence number " + sequence + " exists");
                sequence += sRandom.nextInt(magnitude) + 1;
            }
        }
        throw new StopRequestException(
                DownloadStatus.FILE_ERROR,
                "failed to generate an unused filename on internal download storage");
    }

    /**
     * Checks whether the filename looks legitimate
     */
    static boolean isFilenameValid(String filename, File downloadsDataDir) {
        filename = filename.replaceFirst("/+", "/"); // normalize leading slashes
        return filename.startsWith(Environment.getDownloadCacheDirectory().toString())
                || filename.startsWith(downloadsDataDir.toString())
                || filename.startsWith(Environment.getExternalStorageDirectory().toString());
    }

    /**
     * Checks whether this looks like a legitimate selection parameter
     */
    public static void validateSelection(String selection, Set<String> allowedColumns) {
        try {
            if (selection == null || selection.isEmpty()) {
                return;
            }
            Lexer lexer = new Lexer(selection, allowedColumns);
            parseExpression(lexer);
            if (lexer.currentToken() != Lexer.TOKEN_END) {
                throw new IllegalArgumentException("syntax error");
            }
        } catch (RuntimeException ex) {
            LLog.d("invalid selection [" + selection + "] triggered " + ex);
        }

    }

    // expression <- ( expression ) | statement [AND_OR ( expression ) | statement] *
    //             | statement [AND_OR expression]*
    private static void parseExpression(Lexer lexer) {
        for (; ; ) {
            // ( expression )
            if (lexer.currentToken() == Lexer.TOKEN_OPEN_PAREN) {
                lexer.advance();
                parseExpression(lexer);
                if (lexer.currentToken() != Lexer.TOKEN_CLOSE_PAREN) {
                    throw new IllegalArgumentException("syntax error, unmatched parenthese");
                }
                lexer.advance();
            } else {
                // statement
                parseStatement(lexer);
            }
            if (lexer.currentToken() != Lexer.TOKEN_AND_OR) {
                break;
            }
            lexer.advance();
        }
    }

    // statement <- COLUMN COMPARE VALUE
    //            | COLUMN IS NULL
    private static void parseStatement(Lexer lexer) {
        // both possibilities start with COLUMN
        if (lexer.currentToken() != Lexer.TOKEN_COLUMN) {
            throw new IllegalArgumentException("syntax error, expected column name");
        }
        lexer.advance();

        // statement <- COLUMN COMPARE VALUE
        if (lexer.currentToken() == Lexer.TOKEN_COMPARE) {
            lexer.advance();
            if (lexer.currentToken() != Lexer.TOKEN_VALUE) {
                throw new IllegalArgumentException("syntax error, expected quoted string");
            }
            lexer.advance();
            return;
        }

        // statement <- COLUMN IS NULL
        if (lexer.currentToken() == Lexer.TOKEN_IS) {
            lexer.advance();
            if (lexer.currentToken() != Lexer.TOKEN_NULL) {
                throw new IllegalArgumentException("syntax error, expected NULL");
            }
            lexer.advance();
            return;
        }

        // didn't get anything good after COLUMN
        throw new IllegalArgumentException("syntax error after column name");
    }

    /**
     * A simple lexer that recognizes the words of our restricted subset of SQL where clauses
     */
    private static class Lexer {
        public static final int TOKEN_START = 0;
        public static final int TOKEN_OPEN_PAREN = 1;
        public static final int TOKEN_CLOSE_PAREN = 2;
        public static final int TOKEN_AND_OR = 3;
        public static final int TOKEN_COLUMN = 4;
        public static final int TOKEN_COMPARE = 5;
        public static final int TOKEN_VALUE = 6;
        public static final int TOKEN_IS = 7;
        public static final int TOKEN_NULL = 8;
        public static final int TOKEN_END = 9;

        private final String selection;
        private final Set<String> allowedColumns;
        private final char[] chars;
        private int offset = 0;
        private int currentToken = TOKEN_START;

        public Lexer(String selection, Set<String> allowedColumns) {
            this.selection = selection;
            this.allowedColumns = allowedColumns;
            this.chars = new char[this.selection.length()];
            this.selection.getChars(0, chars.length, chars, 0);
            advance();
        }

        public int currentToken() {
            return currentToken;
        }

        public void advance() {
            char[] chars = this.chars;

            // consume whitespace
            while (offset < chars.length && chars[offset] == ' ') {
                ++offset;
            }

            // end of input
            if (offset == chars.length) {
                currentToken = TOKEN_END;
                return;
            }

            // "("
            if (chars[offset] == '(') {
                ++offset;
                currentToken = TOKEN_OPEN_PAREN;
                return;
            }

            // ")"
            if (chars[offset] == ')') {
                ++offset;
                currentToken = TOKEN_CLOSE_PAREN;
                return;
            }

            // "?"
            if (chars[offset] == '?') {
                ++offset;
                currentToken = TOKEN_VALUE;
                return;
            }

            // "=" and "=="
            if (chars[offset] == '=') {
                ++offset;
                currentToken = TOKEN_COMPARE;
                if (offset < chars.length && chars[offset] == '=') {
                    ++offset;
                }
                return;
            }

            // ">" and ">="
            if (chars[offset] == '>') {
                ++offset;
                currentToken = TOKEN_COMPARE;
                if (offset < chars.length && chars[offset] == '=') {
                    ++offset;
                }
                return;
            }

            // "<", "<=" and "<>"
            if (chars[offset] == '<') {
                ++offset;
                currentToken = TOKEN_COMPARE;
                if (offset < chars.length && (chars[offset] == '=' || chars[offset] == '>')) {
                    ++offset;
                }
                return;
            }

            // "!="
            if (chars[offset] == '!') {
                ++offset;
                currentToken = TOKEN_COMPARE;
                if (offset < chars.length && chars[offset] == '=') {
                    ++offset;
                    return;
                }
                throw new IllegalArgumentException("Unexpected character after !");
            }

            // columns and keywords
            // first look for anything that looks like an identifier or a keyword
            //     and then recognize the individual words.
            // no attempt is made at discarding sequences of underscores with no alphanumeric
            //     characters, even though it's not clear that they'd be legal column names.
            if (isIdentifierStart(chars[offset])) {
                int startOffset = offset;
                ++offset;
                while (offset < chars.length && isIdentifierChar(chars[offset])) {
                    ++offset;
                }
                String word = selection.substring(startOffset, offset);
                if (offset - startOffset <= 4) {
                    if (word.equals("IS")) {
                        currentToken = TOKEN_IS;
                        return;
                    }
                    if (word.equals("OR") || word.equals("AND")) {
                        currentToken = TOKEN_AND_OR;
                        return;
                    }
                    if (word.equals("NULL")) {
                        currentToken = TOKEN_NULL;
                        return;
                    }
                }
                if (allowedColumns.contains(word)) {
                    currentToken = TOKEN_COLUMN;
                    return;
                }
                throw new IllegalArgumentException("unrecognized column or keyword");
            }

            // quoted strings
            if (chars[offset] == '\'') {
                ++offset;
                while (offset < chars.length) {
                    if (chars[offset] == '\'') {
                        if (offset + 1 < chars.length && chars[offset + 1] == '\'') {
                            ++offset;
                        } else {
                            break;
                        }
                    }
                    ++offset;
                }
                if (offset == chars.length) {
                    throw new IllegalArgumentException("unterminated string");
                }
                ++offset;
                currentToken = TOKEN_VALUE;
                return;
            }

            // anything we don't recognize
            throw new IllegalArgumentException("illegal character: " + chars[offset]);
        }

        private static boolean isIdentifierStart(char c) {
            return c == '_' ||
                    (c >= 'A' && c <= 'Z') ||
                    (c >= 'a' && c <= 'z');
        }

        private static boolean isIdentifierChar(char c) {
            return c == '_' ||
                    (c >= 'A' && c <= 'Z') ||
                    (c >= 'a' && c <= 'z') ||
                    (c >= '0' && c <= '9');
        }
    }

    /**
     * Replace invalid filename characters according to
     * specifications of the VFAT.
     *
     * @note Package-private due to testing.
     */
    private static String replaceInvalidVfatCharacters(String filename) {
        final char START_CTRLCODE = 0x00;
        final char END_CTRLCODE = 0x1f;
        final char QUOTEDBL = 0x22;
        final char ASTERISK = 0x2A;
        final char SLASH = 0x2F;
        final char COLON = 0x3A;
        final char LESS = 0x3C;
        final char GREATER = 0x3E;
        final char QUESTION = 0x3F;
        final char BACKSLASH = 0x5C;
        final char BAR = 0x7C;
        final char DEL = 0x7F;
        final char UNDERSCORE = 0x5F;

        StringBuffer sb = new StringBuffer();
        char ch;
        boolean isRepetition = false;
        for (int i = 0; i < filename.length(); i++) {
            ch = filename.charAt(i);
            if ((START_CTRLCODE <= ch &&
                    ch <= END_CTRLCODE) ||
                    ch == QUOTEDBL ||
                    ch == ASTERISK ||
                    ch == SLASH ||
                    ch == COLON ||
                    ch == LESS ||
                    ch == GREATER ||
                    ch == QUESTION ||
                    ch == BACKSLASH ||
                    ch == BAR ||
                    ch == DEL) {
                if (!isRepetition) {
                    sb.append(UNDERSCORE);
                    isRepetition = true;
                }
            } else {
                sb.append(ch);
                isRepetition = false;
            }
        }
        return sb.toString();
    }
}

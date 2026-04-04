package com.sqlnormalize;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Windows / Excel 向けに HTML 断片をクリップボードへ載せる（CF_HTML ヘッダ付き UTF-8）。
 * プレーンテキストも同時に載せ、Excel 以外では TSV 等が使えるようにする。
 */
public final class HtmlWindowsClipboard {

    private static final DataFlavor HTML_STRING_FLAVOR;

    static {
        try {
            HTML_STRING_FLAVOR = new DataFlavor("text/html; charset=UTF-8; class=java.lang.String");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private HtmlWindowsClipboard() {
    }

    /**
     * {@code body} 内の HTML（表や span など）を CF_HTML でクリップボードに置き、{@code plainText} を text/plain でも提供する。
     */
    public static void setHtmlFragmentWithPlainText(String htmlFragment, String plainText) {
        byte[] payload = buildCfHtmlPayload(htmlFragment);
        String htmlAsString = new String(payload, StandardCharsets.UTF_8);
        Transferable t = new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[] { HTML_STRING_FLAVOR, DataFlavor.stringFlavor };
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return HTML_STRING_FLAVOR.equals(flavor) || DataFlavor.stringFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
                if (HTML_STRING_FLAVOR.equals(flavor)) {
                    return htmlAsString;
                }
                if (DataFlavor.stringFlavor.equals(flavor)) {
                    return plainText != null ? plainText : "";
                }
                throw new UnsupportedFlavorException(flavor);
            }
        };
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(t, null);
    }

    /**
     * CF_HTML: 先頭の ASCII ヘッダと UTF-8 の HTML 本体。オフセットは UTF-8 バイト基準。
     */
    static byte[] buildCfHtmlPayload(String htmlFragment) {
        String headOpen = "<html><head><meta charset=\"utf-8\"></head><body>";
        String startMark = "<!--StartFragment-->";
        String endMark = "<!--EndFragment-->";
        String foot = "</body></html>";
        String core = headOpen + startMark + htmlFragment + endMark + foot;
        byte[] coreBytes = core.getBytes(StandardCharsets.UTF_8);

        int headerLen = ("Version:0.9\r\n"
                + "StartHTML:00000000\r\n"
                + "EndHTML:00000000\r\n"
                + "StartFragment:00000000\r\n"
                + "EndFragment:00000000\r\n").getBytes(StandardCharsets.US_ASCII).length;
        int startHtml = headerLen;
        int endHtml = startHtml + coreBytes.length;
        int startFragment = startHtml
                + (headOpen + startMark).getBytes(StandardCharsets.UTF_8).length;
        int endFragment = startHtml
                + (headOpen + startMark + htmlFragment).getBytes(StandardCharsets.UTF_8).length;

        String header = "Version:0.9\r\n"
                + "StartHTML:" + formatOffset(startHtml) + "\r\n"
                + "EndHTML:" + formatOffset(endHtml) + "\r\n"
                + "StartFragment:" + formatOffset(startFragment) + "\r\n"
                + "EndFragment:" + formatOffset(endFragment) + "\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);

        byte[] out = new byte[headerBytes.length + coreBytes.length];
        System.arraycopy(headerBytes, 0, out, 0, headerBytes.length);
        System.arraycopy(coreBytes, 0, out, headerBytes.length, coreBytes.length);
        return out;
    }

    private static String formatOffset(int v) {
        return String.format(Locale.US, "%08d", v);
    }
}

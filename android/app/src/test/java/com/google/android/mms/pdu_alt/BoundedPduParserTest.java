package com.google.android.mms.pdu_alt;

import java.io.ByteArrayInputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class BoundedPduParserTest {
    @Test public void uintvarOverflowAndTruncationFailClosed() {
        byte[][] invalid = { {(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,0x7f}, {(byte)0x80}, {(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80,0} };
        for(byte[] bytes:invalid) {
            try { BoundedPduParser.parseUnsignedInt(new ByteArrayInputStream(bytes)); fail("Malformed length accepted"); }
            catch(IllegalArgumentException expected) { }
        }
        assertEquals(128,BoundedPduParser.parseUnsignedInt(new ByteArrayInputStream(new byte[]{(byte)0x81,0})));
    }
    @Test public void bogusPartCountCannotAllocateOrLoop() {
        try {new BoundedPduParser(new byte[0]).parseParts(new ByteArrayInputStream(new byte[]{0x7f}));fail("Part count accepted");}
        catch(IllegalArgumentException expected) { }
    }
    @Test public void oversizedSkippedValueRejectedBeforeAllocation() {
        try {BoundedPduParser.skipWapValue(new ByteArrayInputStream(new byte[]{1}),Integer.MAX_VALUE);fail("Length accepted");}
        catch(IllegalArgumentException expected) { }
    }
}

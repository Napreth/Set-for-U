package com.napreth.set4u.bridge;
import android.os.ParcelFileDescriptor;
import com.napreth.set4u.bridge.IBridgeProcess;
interface IBridgeService {
    IBridgeProcess start(String command, in ParcelFileDescriptor stdin, in ParcelFileDescriptor stdout, in ParcelFileDescriptor stderr) = 1;
    ParcelFileDescriptor openFile(String path, boolean write) = 2;
    void releaseAll() = 3;
    void destroy() = 16777114;
}
package com.winlator.xserver;

import android.util.SparseArray;

import com.winlator.sysvshm.SysVSharedMemory;

import java.nio.ByteBuffer;

public class SHMSegmentManager {
    private final SysVSharedMemory sysVSharedMemory;
    private final SparseArray<ByteBuffer> shmSegments = new SparseArray<>();

    public SHMSegmentManager(SysVSharedMemory sysVSharedMemory) {
        this.sysVSharedMemory = sysVSharedMemory;
    }

    /**
     * @return false when the shmid is not one of ours and nothing was attached. Reporting that back
     * matters: a client that believes the segment is attached goes on to ShmPutImage, which fails far
     * from the cause, whereas a failed ShmAttach makes Mesa's own probe fall back to plain PutImage.
     */
    public boolean attach(int xid, int shmid) {
        if (shmSegments.indexOfKey(xid) >= 0) detach(xid);
        ByteBuffer data = sysVSharedMemory.attach(shmid);
        if (data == null) return false;
        shmSegments.put(xid, data);
        return true;
    }

    public void detach(int xid) {
        ByteBuffer data = shmSegments.get(xid);
        if (data != null) {
            sysVSharedMemory.detach(data);
            shmSegments.remove(xid);
        }
    }

    public ByteBuffer getData(int xid) {
        return shmSegments.get(xid);
    }
}

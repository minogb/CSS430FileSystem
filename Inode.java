
import java.util.logging.Level;
import java.util.logging.Logger;
/**
 * The Inode, or file that the filesystem manages and can be referenced in
 * process's tcb
 */
public class Inode 
{
    //public static variables
    /**
     * Variable used to mark and check if file is being read
     */
    public static final short MARKED_FOR_READ = 0x01;
    /**
     * Variable used to mark and check if file is being writen
     */
    public static final short MARKED_FOR_WRITE = 0x02;
    /**
     * Variable used to mark and check if file is being  or will be deleted
     */
    public static final short MARKED_FOR_DEATH = 0x04;
    /**
     * Maximum amount of data that can be stored in a file
     */
    public static final int MAX_FILE_SIZE = 136704;
    
    /**
     * The size of each inode
     */
    private final static int iNodeSize = 32;       
    /**
     * the number of inodes that can be stored in each block
     */
    private final static short iNodesPerBlock = 16;
    /**
     * the number of direct pointers to block data
     */
    private final static int directSize = 11;      // # direct pointers
    /**
     * the file size
     */
    public int length;
    /**
     * the number of process with the file open
     */
    public short count;
    /**
     * number of process reading the file
     */
    public byte readerCount = 0;
    /**
     * variable to keep track if being read/writen or marked for deletion
     */
    public byte flag;
    /**
     * array of direct pointers to data blocks
     */
    public short direct[] = new short[directSize]; 
    /**
     * pointer to the block of data containing pointers to blocks of data
     */
    public short indirect;


    /**
     * create an inode with 0'd out information
     */
    Inode() {
        invalidate();
    }
    /**
     * load an inode from memory
     * @param iNumber the place it can be found in memory
     */
    Inode(short iNumber) {
        // If at any point the iNode cannot be created, immediately invalidate
        // all values.
        if (iNumber < 0 || iNumber > SuperBlock.totalInodes) {
            invalidate();
            return;
        }
        //the block the inode can be found in
        short blockNum = (short) (iNumber / iNodesPerBlock + 1);

        if (blockNum > SuperBlock.totalBlocks) {
            invalidate();
            return;
        }

        byte[] block = new byte[Disk.blockSize];
        //read the containing block
        if (SysLib.rawread(blockNum, block) < 0) {
            invalidate();
            return;
        }
        //the offset the inode can be found in memory
        int offset = iNumber % iNodesPerBlock * iNodeSize;
        //load in the information of the inode
        length = SysLib.bytes2int(block, offset);
        count = SysLib.bytes2short(block, offset + 4);
        flag = block[offset + 6];
        flag = block[offset + 7];
        //load in the direct pointers
        for (int i = 0; i < directSize; i++) {
            direct[i] = SysLib.bytes2short(block, offset + 8 + i * 2);
        }
        //load in the pointer to indirect data
        indirect = SysLib.bytes2short(block, offset + 30);
    }
    /**
     * It is the caller's job to ensure that there are no data races when calling
     * this method. Since there is no way of "acquiring" a specific block on disk
     * The caller must wait for this function to return before calling another
     * inode toDisk method.
     * @param iNumber 
     * @return 
     */
    public int toDisk(short iNumber) {
        // Are mutex's needed here?
        // I think that if multiple people are writing or can write to the disk
        // at once, race conditions could be created...
        
        //validity check
        if (iNumber < 0 || iNumber > SuperBlock.totalInodes) {
            return -1;
        }
        //the block that contains the inode
        short blockNum = (short) (iNumber / iNodesPerBlock + 1);

        //validity check
        if (blockNum > SuperBlock.totalBlocks) {
            return -1;
        }

        byte[] block = new byte[Disk.blockSize];
        //read in the block that contains the inode so we can write to it
        if (SysLib.rawread(blockNum, block) < 0) {
            return -1;
        }
        //the offset for the inode
        int offset = iNumber % iNodesPerBlock * iNodeSize;
        SysLib.int2bytes(length, block, offset);
        SysLib.short2bytes(count, block, offset + 4);
        block[offset + 6] = flag;
        block[offset + 7] = readerCount;
        for (int i = 0; i < directSize; i++) {
            SysLib.short2bytes(direct[i], block, offset + 8 + i * 2);
        }
        SysLib.short2bytes(indirect, block, offset + 30);
        //write the block back to memory then return its success/error
        return SysLib.rawwrite(blockNum, block);
    }
    /**
     * get the inode and its block to a byte[]
     * @param data to save to
     * @param iNumber 
     * @return 
     */
    public int toBlockData(byte[] data, short iNumber) {
        //validity check
        if (iNumber < 0 || iNumber > SuperBlock.totalInodes || data.length != Disk.blockSize) {
            return -1;
        }
        //block containing the inode
        short blockNum = (short) (iNumber / iNodesPerBlock + 1);
        //validity check
        if (blockNum > SuperBlock.totalBlocks) {
            return -1;
        }
        //write inode info to data[]
        int offset = iNumber % iNodesPerBlock * iNodeSize;
        SysLib.int2bytes(length, data, offset);
        SysLib.short2bytes(count, data, offset + 4);
        data[offset + 6] = flag;
        data[offset + 7] = readerCount;
        for (int i = 0; i < directSize; i++) {
            SysLib.short2bytes(direct[i], data, offset + 8 + i * 2);
        }
        SysLib.short2bytes(indirect, data, offset + 30);
        //return success
        return 0;
    }
    /**
     * fill the inode with default/empty data
     */
    public void invalidate() {
        length = 0;
        count = 0;
        flag = 0;
        for (int i = 0; i < directSize; i++) {
            direct[i] = -1;
        }
        indirect = -1;
    }
    /**
     * wait until everyone is done writing
     */
    public void waitUntilAccessable() {
        while ((flag & MARKED_FOR_WRITE) != 0) {
            try {
                wait();
            } catch (InterruptedException ex) {

            }
        }
    }
    /**
     * wake up threads
     */
    public void finishAccessable() {
        notify();
    }
    /**
     * mark he file for deletion
     */
    public void markForDeath() {
        flag |= MARKED_FOR_DEATH;
    }
    /**
     * check if the file is marked for deletion
     * @return if it is
     */
    public boolean isDying() {
        return (flag & MARKED_FOR_DEATH) != 0;
    }
    /**
     * wait untill everyone is done writing so we can read
     */
    public synchronized void waitRead() {
        while ((flag & MARKED_FOR_WRITE) != 0 && readerCount < 255) {
            try {
                wait();
            } catch (InterruptedException ex) {

            }
        }

        readerCount++;

        flag |= MARKED_FOR_READ;

        notify(); // Attempt to wake up other reads. If we wake up a write, it will just get ignored.
    }
    /**
     * free up those waiting to read/write
     */
    public synchronized void finishRead() {
        readerCount--;

        if (readerCount == 0) {
            flag ^= MARKED_FOR_READ;
        }

        notify();
    }
    /**
     * wait for everyone to finish wrting/reading
     */
    public synchronized void waitWrite() {
        while ((flag & MARKED_FOR_WRITE) != 0 || (flag & MARKED_FOR_READ) != 0) {
            try {
                wait();
            } catch (InterruptedException ex) {

            }
        }

        flag |= MARKED_FOR_WRITE;
    }
    /**
     * notify the system we are done writing
     */
    public synchronized void finishWrite() {
        flag ^= MARKED_FOR_WRITE;

        notify();
    }
}


/**
 * Superblock manages all blocks, not files or inodes
 */
class SuperBlock 
{
    //public statics
    /**
     * The total number of blocks this system has access to
     */
    public static int totalBlocks;
    /**
     * the total number of inodes this system can have access to
     */
    public static int totalInodes;
    /**
     * pointer to the first free block
     */
    public static int freeList;   
    /**
     * default number of inodes sugested by faq on assignment
     */
    public static final int DEFAULT_INODES = 64;
    /**
     * the number of inodes one block can contain
     */
    public static final int INODES_PER_BLOCK = 16;
    /**
     * default number of blocks spec by faq/assignment
     */
    public static final int DEFAULT_NUM_BLOCKS = 1000;

    //private statics 
    /**
     * value that is used for returns when we are out of blocks
     */
    private static final short NO_MORE_BLOCKS = -1;
    /**
     * Create a superblock for a filesystem
     * @param diskSize the size of our disk
     */
    public SuperBlock(int diskSize) {
        byte[] block = new byte[Disk.blockSize];
        //if invalid, invalidate superblock block
        if (SysLib.rawread(0, block) < 0) {
            invalidate();
            return;
        }
        //save information to a byte[] tosave to disk
        totalBlocks = SysLib.bytes2int(block, 0);
        totalInodes = SysLib.bytes2int(block, 4);
        freeList = SysLib.bytes2int(block, 8);
        //checks to see if the disk is already formated
        if (totalBlocks == diskSize && totalInodes > 0 && freeList > 1) {
            //it is, do nothing
        } else //otherwise we need to start to format
        {
            //fix any missmatch
            totalBlocks = diskSize;
            totalInodes = DEFAULT_INODES;
            freeList = totalInodes / INODES_PER_BLOCK + 1;
        }

        SysLib.int2bytes(totalBlocks, block, 0);
        SysLib.int2bytes(totalInodes, block, 4);
        SysLib.int2bytes(freeList, block, 8);

        SysLib.rawwrite(0, block);
    }
    /**
     * used to invalidate current superblock information
     */
    public void invalidate() {
        totalBlocks = 0;
        totalInodes = 0;
        freeList = 0;
    }
    /**
     * This function is to format the disk. Info for the superblock is
     * located/stored in block 0
     * @param numInodes the # the system can keep track of
     */
    public synchronized void formatDisk(int numInodes) {
        // Each inode should be able to store at least one block of data
        if (numInodes < 0 || numInodes > Disk.blockSize / 2 - 1) {
            return;
        }

        byte[] block = new byte[Disk.blockSize];
        totalInodes = numInodes;

        //get the pointer to the first free block, check for correct offset for reading
        freeList = (numInodes % INODES_PER_BLOCK) == 0
                ? numInodes / INODES_PER_BLOCK + 1 : numInodes / INODES_PER_BLOCK + 2;

        //write the stored data to the block data (block array)
        SysLib.int2bytes(totalBlocks, block, 0);
        SysLib.int2bytes(totalInodes, block, 4);
        SysLib.int2bytes(freeList, block, 8);
        SysLib.rawwrite(0, block);

        for (short i = 0; i < totalInodes; i++) {
            Inode inode = new Inode();
            inode.toBlockData(block, i);

            if (i == INODES_PER_BLOCK || i + 1 == totalInodes) {
                SysLib.cwrite(i / INODES_PER_BLOCK + 1, block);
            }
        }

			//zero out all data inside the array, get rid of junk data
        //or get rid of last times data
        for (int index = 0; index < Disk.blockSize; index++) {
            block[index] = (byte) 0;
        }

        int tenth = totalBlocks / 10;

        //Find the next free block for each block, until we have read all of the blocks
        for (int currentBlock = freeList; currentBlock < totalBlocks; currentBlock++) {
            SysLib.int2bytes((currentBlock + 1 >= totalBlocks) ? -1 : currentBlock + 1, block, 0);

                //if at the end set the next free block we are invalid, other wise, set to next
            //we will be saving this info as a short, not a int
            SysLib.cwrite(currentBlock, block);

            if (currentBlock % tenth == 0) {
                SysLib.cerr(". ");
            }
        }
    }

    /**
     * save the superblock to disk
     */
    public synchronized void sync() {
        byte[] data = new byte[Disk.blockSize];
        //read in old data
        SysLib.rawread(0, data);
        //copy new information to store to a byte array
        SysLib.int2bytes(totalBlocks, data, 0);
        SysLib.int2bytes(totalInodes, data, 4);
        SysLib.int2bytes(freeList, data, 8);

            //write the information in superblock to disk
        //rawwrite instead of cwrite because we want the information
        //to go directly to the disk.
        SysLib.rawwrite(0, data);
    }
    /**
     * used to get the next free block when  it is needed
     * @return the block number or error
     */
    public synchronized short getNextFreeBlock() {
        short retVal = (short) freeList;
         // indicates that there are no more free blocks
        if (retVal == NO_MORE_BLOCKS)
        {
            return - 1;
        }

        //grab the next free point in the blocks
        byte[] data = new byte[Disk.blockSize];
        //get the next free pointer from the one we are pointing at
        SysLib.cread(freeList, data);

        //first thing in the block
        short nextFree = (short) SysLib.bytes2int(data, 0);

        // Clear the block before giving it away.
        for (int i = 0; i < Disk.blockSize; i++) {
            data[i] = 0;
        }
        SysLib.cwrite(freeList, data);

        // Next free will only equal 0 if the free list has not been fully
        // allocated yet. Since no blocks past this block will be accessed 
        // if this block is zeroed out, the next block is free.
        //
        // The primary motivation behind this methodolgy is that manually
        // setting the entire free list on startup is quite time consuming,
        // and is unnecessary if we assume that the disk is all 0s to start.
        // This optimization is VERY implementation specific.
        if (nextFree == 0) {
            if (nextFree >= totalInodes) // If we have allocated every block, make free list indicate this.
            {
                freeList = NO_MORE_BLOCKS;
            } else {
                freeList++;
            }
        } else {
            freeList = nextFree;
        }

        return retVal;
    }
    /**
     * give a block back to the superblock
     * @param blockNum the block to give back
     * @return the number of the block freed or error
     */
    public int returnBlock(short blockNum) {
        int retVal = -1;
        //check validity
        if (blockNum < 0 || blockNum > totalBlocks) {
            return retVal;
        }

        byte[] data = new byte[Disk.blockSize];
            //our new block is going to be the new end of the free list
        //aka what the freelist will be pointing at
        SysLib.int2bytes(freeList, data, 0);
        //write the new data to the block
        SysLib.cwrite(blockNum, data);
        freeList = blockNum; // setting freelist to this block
        return blockNum;
    }
}

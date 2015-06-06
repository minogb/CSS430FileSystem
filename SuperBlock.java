class SuperBlock
{
	public int totalBlocks; // the number of disk blocks
	public int totalInodes; // the number of inodes
	public int freeList;    // the block number of the free list's head
        //default sugested by faq on assignment
        private final int DEFAULT_INODES = 64;
        private final int DATA_SIZE = 512;
        //default number of blocks spec by faq/assignment
        private final int DEFAULT_NUM_BLOCKS = 1000;
        public SuperBlock(int diskSize)
        {
            byte[] block = null;
            if (SysLib.rawread(0, block) < 0)
            {
                    invalidate();
                    return;
            }
            totalBlocks = SysLib.bytes2Int(block, 0);
            totalInodes = SysLib.bytes2Int(block, 4);
            freeList = SysLib.bytes2Int(block, 8);
            //checks to see if the disk is already formated
            if(totalBlocks == diskSize && totalInodes > 0 && freeList > 1)
            {
                //it is, do nothing
            }
            else //otherwise we need to start to format
            {
                //fix any missmatch
                totalBlocks = diskSize;
                
            }
        }
        //This function is to format the disk. Info for the superblock is
        //located/stored in block 0
        private synchronized  void formatDisk(int numInodes)
        {
            byte[] block = new byte[DATA_SIZE];
            totalBlocks = DEFAULT_NUM_BLOCKS;
            totalInodes = numInodes;
            //get the pointer to the first free block, check for correct offset for reading
            freeList = (numInodes % 16) == 0 ? numInodes / 16 + 1 : numInodes / 16 + 2;
            //write the stored data to the block data (block array)
            SysLib.int2bytes(totalBlocks, block, 0);
            SysLib.int2bytes(totalInodes, block, 4);
            SysLib.int2bytes(freeList, block, 8);
            byte[] toStore = new byte[DATA_SIZE];
            //Find the next free block for each block, untill we have read all of the blocks
            for(int currentBlock = freeList; currentBlock < totalBlocks; currentBlock++)
            {
                //zero out all data inside the array, get rid of junk data
                //or get rid of last times data
                for(int jndex = 0; jndex < totalBlocks; jndex++)
                {
                    toStore[jndex] = (byte)0;
                }
                //if at the end set the next free block we are invalid, other wise, set to next
                //we will be saving this info as a short, not a int
                short nextFreeBlock = (short) (currentBlock == (totalBlocks-1) ? 0 : currentBlock+1);
                SysLib.short2bytes(nextFreeBlock, toStore, 0);
                SysLib.rawwrite(currentBlock,toStore);
            }
        }
        
        //save back to disk
        public synchronized void sync()
        {
            byte[] data = new byte[DATA_SIZE];
            //read in old data
            SysLib.rawread(0, data);
            //copy new information to store to a byte array
            SysLib.int2bytes(totalBlocks, data, 0);
            SysLib.int2bytes(totalInodes, data, 4);
            SysLib.int2bytes(freeList, data, 8);
            //write the information in superblock to disk
            SysLib.rawwrite(0,data);
        }
        public synchronized short getNextFreeBlock()
        {
            short retVal = (short)freeList;
            //grab the next free point in the blocks
            byte[] data = new byte[DATA_SIZE];
            //get the next free pointer from the one we are pointing at
            SysLib.rawread(freeList,data);
            //first thing in the block
            freeList = (int)SysLib.bytes2short(data,0);
            return retVal;
        }
        //free given block
        public int returnBlock(short blockNum)
        {
            int retVal = -1;
            //check validity
            if(blockNum < 0 || blockNum > totalBlocks)
            {
                return retVal;
            }
            byte[] data = new byte[DATA_SIZE];
            //our new block is going to be the new end of the free list
            //aka what the freelist will be pointing at
            SysLib.int2bytes(freeList, data, 0);
            //write the new data to the block
            SysLib.rawwrite(blockNum, data);
            freeList = blockNum; // setting freelist to this block
            return blockNum;
        }
}

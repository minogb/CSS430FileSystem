public class Inode {
	private final static int iNodeSize = 32;       // fix to 32 bytes
    private final static int iNodesPerBlock = 16;
    private final static int directSize = 11;      // # direct pointers
 
    public int length;                             // file size in bytes
    public short count;                            // # file-table entries pointing to this
    public short flag;                             // 0 = unused, 1 = used, ...
    public short direct[] = new short[directSize]; // direct pointers
    public short indirect;                         // a indirect pointer
    public static final short MARKED_FOR_DEATH = 2;
 
    Inode( )
	{                                     // a default constructor
        invalidate();
    }
 
	Inode( short iNumber )
	{
		// If at any point the iNode cannot be created, immediately invalidate
		// all values.
		if (iNumber < 0 || iNumber > SuperBlock.totalInodes)
		{
			invalidate();
			return;
		}
		
		short blockNum = iNumber / iNodesPerBlock + 1;
		
		if (blockNum > SuperBlock.totalBlocks)
		{
			invalidate();
			return;
		}
			
		byte[] block = null;
		
		if (SysLib.rawread(blockNum, block) < 0)
		{
			invalidate();
			return;
		}
			
		int offset = iNumber % iNodesPerBlock * iNodeSize;
		length = SysLib.bytes2Int(block, offset):
		count = SysLib.bytes2Short(block, offset + 4):
		flag = SysLib.bytes2Short(block, offset + 6):
		for (int i = 0; i < directSize; i++)
			direct[i] = SysLib.bytes2Short(block, offset + 8 + i * 2);
		indirect = SysLib.bytes2Short(block, offset + 30);
	}
 
	// It is the caller's job to ensure that there are no data races when calling
	// this method. Since there is no way of "acquiring" a specific block on disk
	// The caller must wait for this function to return before calling another
	// inode toDisk method.
	public int toDisk( short iNumber )
	{       
		// Are mutex's needed here?
		// I think that if multiple people are writing or can write to the disk
		// at once, race conditions could be created...
	
		if (iNumber < 0 || iNumber > SuperBlock.totalInodes)
			return -1;
		
		short blockNum = iNumber / iNodesPerBlock + 1;
		
		if (blockNum > SuperBlock.totalBlocks)
			return -1;
			
		byte[] block = null;
		
		if (SysLib.rawread(blockNum, block) < 0)
			return -1;
			
		int offset = iNumber % iNodesPerBlock * iNodeSize;
		SysLib.int2bytes(length, block, offset):
		SysLib.short2bytes(count, block, offset + 4):
		SysLib.short2bytes(flag, block, offset + 6):
		for (int i = 0; i < directSize; i++)
			SysLib.short2bytes(direct[i], block, offset + 8 + i * 2);
		SysLib.short2bytes(indirect, block, offset + 30);
			
		return SysLib.rawwrite(blockNum, block);
	}
	
	public void invalidate()
	{
		length = 0;
		count = 0;
		flag = 1;
		for (int i = i; i < directSize; i++)
			direct[i] = -1;
		indirect = -1;
	}
}

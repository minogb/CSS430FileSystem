
import java.util.logging.Level;
import java.util.logging.Logger;

public class Inode {
	private final static int iNodeSize = 32;       // fix to 32 bytes
    private final static short iNodesPerBlock = 16;
    private final static int directSize = 11;      // # direct pointers
 
    public int length;                             // file size in bytes
    public short count;                            // # file-table entries pointing to this
	public byte readerCount = 0;
    public byte flag;                             // 0 = unused, 1 = used, ...
    public short direct[] = new short[directSize]; // direct pointers
    public short indirect;                         // a indirect pointer
	
	public static final short MARKED_FOR_READ = 0x01;
	public static final short MARKED_FOR_WRITE = 0x02;
    public static final short MARKED_FOR_DEATH = 0x04;
	
	public static final int MAX_FILE_SIZE = 136704;
 
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
		
		short blockNum = (short) (iNumber / iNodesPerBlock + 1);
		
		if (blockNum > SuperBlock.totalBlocks)
		{
			invalidate();
			return;
		}
			
		byte[] block = new byte[Disk.blockSize];
		
		if (SysLib.rawread(blockNum, block) < 0)
		{
			invalidate();
			return;
		}
			
		int offset = iNumber % iNodesPerBlock * iNodeSize;
		length = SysLib.bytes2int(block, offset);
		count = SysLib.bytes2short(block, offset + 4);
		flag = block[offset + 6];
		flag = block[offset + 7];
		
		for (int i = 0; i < directSize; i++)
			direct[i] = SysLib.bytes2short(block, offset + 8 + i * 2);
		indirect = SysLib.bytes2short(block, offset + 30);
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
		
		short blockNum = (short) (iNumber / iNodesPerBlock + 1);
		
		if (blockNum > SuperBlock.totalBlocks)
			return -1;
			
		byte[] block = new byte[Disk.blockSize];
		
		if (SysLib.rawread(blockNum, block) < 0)
			return -1;
			
		int offset = iNumber % iNodesPerBlock * iNodeSize;
		SysLib.int2bytes(length, block, offset);
		SysLib.short2bytes(count, block, offset + 4);
		block[offset + 6] = flag;
		block[offset + 7] = readerCount;
		for (int i = 0; i < directSize; i++)
			SysLib.short2bytes(direct[i], block, offset + 8 + i * 2);
		SysLib.short2bytes(indirect, block, offset + 30);
			
		return SysLib.rawwrite(blockNum, block);
	}
	
	public int toBlockData(byte[] data, short iNumber)
	{
		if (iNumber < 0 || iNumber > SuperBlock.totalInodes || data.length != Disk.blockSize)
			return -1;
		
		short blockNum = (short) (iNumber / iNodesPerBlock + 1);
		
		if (blockNum > SuperBlock.totalBlocks)
			return -1;
			
		int offset = iNumber % iNodesPerBlock * iNodeSize;
		SysLib.int2bytes(length, data, offset);
		SysLib.short2bytes(count, data, offset + 4);
		data[offset + 6] = flag;
		data[offset + 7] = readerCount;
		for (int i = 0; i < directSize; i++)
			SysLib.short2bytes(direct[i], data, offset + 8 + i * 2);
		SysLib.short2bytes(indirect, data, offset + 30);
			
		return 0;
	}
	
	public void invalidate()
	{
		length = 0;
		count = 0;
		flag = 0;
		for (int i = 0; i < directSize; i++)
			direct[i] = -1;
		indirect = -1;
	}
	
	public void waitUntilAccessable()
	{
		while ((flag & MARKED_FOR_WRITE) != 0)
			try {
                            wait();
                    } catch (InterruptedException ex) {
                     
                    }
	}
	
	public void finishAccessable()
	{
		notify();
	}
	
	public void markForDeath()
	{
		flag |= MARKED_FOR_DEATH;
	}
	
	public boolean isDying()
	{
		return (flag & MARKED_FOR_DEATH) != 0;
	}
	
	public synchronized void waitRead()
	{
		while ((flag & MARKED_FOR_WRITE) != 0 && readerCount < 255)
			try {
                            wait();
                    } catch (InterruptedException ex) {
                        
                    }
			
		readerCount++;
			
		flag |= MARKED_FOR_READ;
		
		notify(); // Attempt to wake up other reads. If we wake up a write, it will just get ignored.
	}
	
	public synchronized void finishRead()
	{
		readerCount--;
		
		if (readerCount == 0)
			flag ^= MARKED_FOR_READ;
		
		notify();
	}

	public synchronized void waitWrite()
	{
		while ((flag & MARKED_FOR_WRITE) != 0 || (flag & MARKED_FOR_READ) != 0)
			try {
                            wait();
                    } catch (InterruptedException ex) {
                     
                    }
		
		flag |= MARKED_FOR_WRITE;
	}
	
	public synchronized void finishWrite()
	{
		flag ^= MARKED_FOR_WRITE;
		
		notify();
	}
}

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileSystem
{
	//Interface Variables
	public Disk disk;
	public Scheduler scheduler; // Only for getMyTcb

	public static final int SEEK_SET = 0;
	public static final int SEEK_CUR = 1;
	public static final int SEEK_END = 2;

	//Instance Variables
	private SuperBlock superBlock;
	private Directory dir;
	private FileTable fileTable;
	
	
	private int fileCount;
	
	private static final int DIR_INODE = 0;
	
	private static final int SUCCESS = 0;
	private static final int ERROR = -1;

	public FileSystem(Disk _disk, Scheduler _scheduler)
	{
		disk = _disk;
		scheduler = _scheduler;
		superBlock = new SuperBlock(_disk.getDiskSize());
		
		
		fileCount = SuperBlock.DEFAULT_INODES;
		
		dir = new Directory(SuperBlock.DEFAULT_INODES);
		byte[] dirData = getDiskDirData();
		dir.bytes2directory(dirData);
		
		fileTable = new FileTable(dir);
	}
	
	public int open(String filename, String mode)
	{
		SysLib.cerr("\nint open(String filename, String mode)\n");
	
		if (!(mode == "r" || mode == "w" || mode == "w+" || mode == "a"))
			return ERROR;
			
		SysLib.cerr("  Found right mode.\n");
			
		TCB tcb = scheduler.getMyTcb();
		if (tcb == null)
			return ERROR;
			
		SysLib.cerr("  Found thread's tcb\n");
			
		int fd = 3;
		for (;fd < tcb.ftEnt.length; fd++)
			if (tcb.ftEnt[fd] == null)
				break;
				
		if (fd >= tcb.ftEnt.length)
			return ERROR;
			
		SysLib.cerr("  Found an open fd slot.\n");
			
		FileTableEntry entry = fileTable.falloc(filename, mode);
		if (entry == null)
			return ERROR;
			
		SysLib.cerr("  A new FileTableEntry was created.\n");
			
		tcb.ftEnt[fd] = entry;
		
		SysLib.cerr("returning from open.\n");
		return fd;
	}
	
	public synchronized int close(int fd)
	{
            if (fd < 3)
            {
                    return ERROR;
            }
            TCB tcb = scheduler.getMyTcb();
            if (tcb == null || fd >= tcb.ftEnt.length || tcb.ftEnt[fd] == null)
                return ERROR;

            if(--tcb.ftEnt[fd].inode.count < 1 && tcb.ftEnt[fd].inode.isDying())
            {
                if(!fileTable.ffree(tcb.ftEnt[fd]))
                {
                    return ERROR;
                }
                return delete(tcb.ftEnt[fd].iNumber);
            }
            else if(tcb.ftEnt[fd].inode.count < 1)
                notify();
            if (fileTable.ffree(tcb.ftEnt[fd]))
            {
                tcb.ftEnt[fd] = null;
                return 0;
            }
            else
                return ERROR;
	}
	
	public int seek(int fd, int offset, int whence)
	{
		if (fd < 3)
			return ERROR;
			
		switch (whence)
		{
			case SEEK_SET:
			case SEEK_CUR:
			case SEEK_END:
				break;
			default:
				return ERROR;
		}
	
		TCB tcb = scheduler.getMyTcb();
		if (tcb == null || fd >= tcb.ftEnt.length || tcb.ftEnt[fd] == null)
			return ERROR;
				
		FileTableEntry entry = tcb.ftEnt[fd];
		Inode inode = entry.inode;
				
		switch (whence)
		{
			case SEEK_SET:
				offset = boundSeekPtr(offset, inode);
				break;
			case SEEK_CUR:
				offset = boundSeekPtr(entry.seekPtr + offset, inode);
				break;
			case SEEK_END:
				offset = boundSeekPtr(inode.length + offset, inode);
				break;
		}
		
		short curBlockCount = (short) (inode.length / Disk.blockSize + 1);
		short blockCount = (short) (offset / Disk.blockSize + 1);
		if (curBlockCount < blockCount)
		{
			inode.waitWrite();
			byte[] indirectBlock = new byte[Disk.blockSize];
			if (blockCount > inode.direct.length)
			{
				if (SysLib.cread(inode.indirect, indirectBlock) != 0)
				{
					inode.finishWrite();
					
					return -1;
				}
			}
			
			for (; curBlockCount < blockCount; curBlockCount++)
			{
				if (curBlockCount >= inode.direct.length)
				{
					int newBlock = superBlock.getNextFreeBlock();
					
					// TODO: Add in checks for -1 here. This could cause some serious
					// 		problems
					
					SysLib.int2bytes(newBlock, indirectBlock, 
						(curBlockCount - inode.direct.length) * 2);
				}
				else
				{
					inode.direct[curBlockCount] = superBlock.getNextFreeBlock();
					
					// TODO: Add in -1 checks here.
				}
			}
			
			if (indirectBlock != null)
			{
				//TODO: Handle -1 here.
				
				SysLib.cwrite(inode.indirect, indirectBlock);
			}
			
			inode.finishWrite();
		}
		
		entry.seekPtr = offset;
			
		return entry.seekPtr;
	}
	private synchronized int delete(int inumber)
        {
            if(inumber < 0)
                return -1;
            Inode current = fileTable.table.get(inumber).inode;
            if(current.count > 0)
            {
                current.markForDeath();
                while(current.count > 0)
                    try {
                        wait();
                    } catch (InterruptedException ex) {}
            }
            String fileName = dir.iname((short)inumber);
            for(int i = 0; i < dir.fsize.length;i++)
            {
                if(fileName.equals(dir.fnames[inumber].toString()))
                {
                    //delete the reference in the directory
                    dir.fsize[i] = 0;
                    dir.fnames[i] = "".toCharArray();
                    //delete the refernce in the file table
                    for(int j = 0; j < current.direct.length; j++)
                    {
                        superBlock.returnBlock(current.direct[j]);
                        superBlock.returnBlock((short) (inumber / superBlock.INODES_PER_BLOCK + 1));
                    }
                    if(current.indirect < 0)
                        return -1;
                    byte[] block = new byte[Disk.blockSize];
                    SysLib.cread(current.indirect, block);
                    //delete all indirect
                    for(int j =0; j < block.length; j+=2)
                    {
                        short pointedBlock = SysLib.bytes2short(block, j);
                        if(pointedBlock < 0)
                            break;
                        superBlock.returnBlock(pointedBlock);
                    }
                    fileTable.table.removeElementAt(inumber);
                    return 0;
                }
            }
            return -1;
        }
	public int delete(String filename)
	{
            //lookup filename
            return delete(dir.namei(filename));
	}
	
	public int read(int fd, byte[] buffer)
	{
		if (fd < 3 || buffer == null)
			return ERROR;
	
		TCB tcb = scheduler.getMyTcb();
		if (tcb == null || fd >= tcb.ftEnt.length || tcb.ftEnt[fd] == null)
			return ERROR;
			
		FileTableEntry entry = tcb.ftEnt[fd];
		
		if (!(entry.mode == "r" || entry.mode == "w+")) // Verify the right mode
			return ERROR;
			
		int errVal = SUCCESS;
		
		entry.inode.waitRead(); // Blocking until the current write operation is finished, if any
		
		int blockNum = entry.seekPtr / Disk.blockSize; // Int division truncates remainder
		int blockOffset = entry.seekPtr % Disk.blockSize; // Remainder is the first block offset
		int readSize = (entry.seekPtr + buffer.length > entry.inode.length) ? // Bound the readSize by the size of the filse
			entry.inode.length - entry.seekPtr : 
			buffer.length;
		int blockCount = (blockOffset + readSize) / Disk.blockSize + 1; // Calculate how many blocks will be read (always at least 1)
		
		byte[] blockData = new byte[Disk.blockSize];
		byte[] indirectData = null;
		if (blockNum >= entry.inode.direct.length) // Are we referencing an indirect blockCount
		{
			indirectData = new byte[Disk.blockSize];
			errVal = SysLib.cread(entry.inode.indirect, indirectData);
			
			if (errVal != SUCCESS)
				return ERROR;
				
			errVal = SysLib.cread(SysLib.bytes2int(indirectData, (blockNum - entry.inode.direct.length) * 2), blockData);
                        blockNum++;
			
			if (errVal != SUCCESS)
				return ERROR;
		}
		else // Are we starting the reads at the direct connected blocks
		{
			if (blockNum + blockCount >= entry.inode.direct.length) // If we will be accessing the indirect block, load it now
			{
				indirectData = new byte[Disk.blockSize];
				errVal = SysLib.cread(entry.inode.indirect, indirectData);
				
				if (errVal != SUCCESS)
					return ERROR;
			}
		
			errVal = SysLib.cread(entry.inode.direct[blockNum++], blockData);
			
			if (errVal != SUCCESS)
				return ERROR;
		}
		
		if (blockCount == 1) // Most simple version for small reads
		{
			System.arraycopy(blockData, blockOffset, 
					  buffer, 0, 
					  readSize);
					  
			entry.seekPtr += readSize;
		}
		else
		{
			int bufferOffset = Disk.blockSize - blockOffset; 
			System.arraycopy(blockData, blockOffset, 
					  buffer, 0, 
					  bufferOffset);
		
			for (; blockNum < blockCount; blockNum++)
			{
				// Bound the individual block read size by the size of the block itself
				int blockReadSize = (readSize - bufferOffset > Disk.blockSize) ? 
					Disk.blockSize : readSize - bufferOffset;
			
				if (blockNum >= entry.inode.direct.length) // Are we accessing an indirect block
				{
					errVal = SysLib.cread(SysLib.bytes2int(indirectData, 
                                                (blockNum - entry.inode.direct.length) * 2),
                                                blockData);
				}
				else // Nope, we are a direct block
					errVal = SysLib.cread(entry.inode.direct[blockNum], blockData);
					
				if (errVal != SUCCESS)
					return ERROR;
					
				System.arraycopy(blockData, 0,
					buffer, bufferOffset,
					blockReadSize);
						
				entry.seekPtr += blockReadSize;
				
				bufferOffset += blockReadSize;
			}
		}
		
		entry.inode.finishRead();
				  
		return readSize;
	}
	
	public int write(int fd, byte[] buffer)
	{
		SysLib.cerr("int write(int fd, byte[] buffer)\n");
		if (fd < 3 || buffer == null)
			return ERROR;

		TCB tcb = scheduler.getMyTcb();
		if (tcb == null || fd >= tcb.ftEnt.length || tcb.ftEnt[fd] == null)
			return ERROR;
		
		FileTableEntry entry = tcb.ftEnt[fd];
		int endPoint = buffer.length + entry.seekPtr;
		//starting block
		int dirPtr = entry.seekPtr / Disk.blockSize;
		int dirOff = entry.seekPtr % Disk.blockSize;
		
		SysLib.cerr("  dirPtr: " + dirPtr + ", dirOff: " + dirOff + "\n");
		
		//Check if we are in the bounds of writing to a single block
		if(dirOff + buffer.length < Disk.blockSize)
		{
                        byte[] preloadedData = new byte[entry.seekPtr % dirPtr];
			if(!readHelperForWrite(preloadedData,dirPtr,entry.inode))
                        {
                            preloadedData = null;
                        }
			//Direct?
			if(dirPtr < 11)
			{
				if (entry.inode.direct[dirPtr] == -1)
					entry.inode.direct[dirPtr] = superBlock.getNextFreeBlock();
					
				// If we ever fail this conditional, we have no more room on the disk.
				if (entry.inode.direct[dirPtr] == -1)
					return ERROR;
					
				entry.inode.waitWrite();
					
				SysLib.cerr("  accessing block " + entry.inode.direct[dirPtr] + "\n");
			
				//get the length of stored data
				byte[] data = new byte[Disk.blockSize];
				
				//append data
				System.arraycopy(preloadedData, 0, data, 0, preloadedData.length);
				System.arraycopy(buffer, 0, data, preloadedData.length, buffer.length);
				
				//direct and only one block, so a simple write will do
				if (SysLib.cwrite(entry.inode.direct[dirPtr], data) != 0)
					return -1;
				
				//move the pointer ahead
				entry.seekPtr += buffer.length;
				
				if (endPoint > entry.inode.length)
					entry.inode.length = endPoint;
				
				entry.inode.finishWrite();
				
				SysLib.cerr("returning write()\n");
				return buffer.length;
			}
                        else if(preloadedData != null)
			{

				//indirect single block write

                                byte[] writeableData = new byte[preloadedData.length + buffer.length];
				//append data
				System.arraycopy(preloadedData, 0, writeableData, 0, preloadedData.length);
				System.arraycopy(buffer, 0, writeableData, preloadedData.length, buffer.length);
				//direct and only one block, so a simple write will doe);
                                int blockNm = entry.inode.indirect;
                                byte[] indBlock = new byte[Disk.blockSize];
                                blockNm = SysLib.bytes2short(indBlock, 2*(blockNm-11));
				SysLib.rawwrite(blockNm,writeableData);
				//move the pointer ahead
				entry.seekPtr += buffer.length;
				return buffer.length;
			}
                        else
                        {
                            return ERROR;
                        }
		}
		else
		{
			//multiblock write
                    int numWritten = 0;
                    if(entry.seekPtr % dirPtr != 0)
                    {
                        byte[] data = new byte[entry.seekPtr % dirPtr];
                        readHelperForWrite(data, dirPtr, entry.inode);
                        byte[] writeableData = new byte[Disk.blockSize];
                        //append data
                        System.arraycopy(data, 0, writeableData, 0, data.length);
                        numWritten = Disk.blockSize - entry.seekPtr % dirPtr;
                        System.arraycopy(buffer, 0, writeableData, data.length, numWritten);
                        entry.seekPtr += numWritten;
                    }
                    //int endPoint = buffer.length + entry.seekPtr;
                    for(int i = dirPtr; i < endPoint * Disk.blockSize; i++)
                    {
                        int nextBlock = -1;
                        //direct?
                        if(i < 12)
                        {
                         //   nextBlock
                        }
                        else//inderect
                        {

                        }
                    }
		}
		return ERROR;
	}
	private boolean readHelperForWrite(byte[] returnValue, int blockNum, Inode inode)
        {
            if(blockNum < 0)
                return false;
            if(blockNum < 11)
            {
                blockNum = inode.direct[blockNum];
            }
            else
            {
                SysLib.cread(inode.indirect,returnValue);
                blockNum = SysLib.bytes2short(returnValue, 2*(blockNum-11));
                if(blockNum < 0)
                    return false;
            }
            SysLib.cread(blockNum,returnValue);
            return true;
        }
	public int size(int fd)
	{
		if (fd < 3)
			return ERROR;
	
		TCB tcb = scheduler.getMyTcb();
		if (tcb == null || fd >= tcb.ftEnt.length || tcb.ftEnt[fd] == null)
			return ERROR;
			
		tcb.ftEnt[fd].inode.waitUntilAccessable();
			
		return tcb.ftEnt[fd].inode.length;
	}
	
	public int format(int fileCount)
	{
            //NOTE: Check to see if anyone is open/writing/reading?
            try
            {
                superBlock.formatDisk(fileCount);
                return SUCCESS;
            }
            catch(Exception e)
            {
		return ERROR;
            }
	}
	
	private int boundSeekPtr(int seekPtr, Inode inode)
	{
		return (seekPtr < 0) ? 0 : (seekPtr > inode.length) ? inode.length - 1 : seekPtr;
	}
	
	private byte[] getDiskDirData()
	{
		Inode rootInode = new Inode((short)DIR_INODE); 
		
		int errVal = SUCCESS;
		
		byte[] blockData = new byte[Disk.blockSize];
		int blockCount = 0;
		for (; blockCount < rootInode.direct.length && 
			rootInode.direct[blockCount] > DIR_INODE; 
			blockCount++) ;
			
		byte[] indirect = null;
		int indirectCount = 0;
		if (rootInode.indirect > DIR_INODE)
		{
			indirect = new byte[Disk.blockSize];
			errVal = SysLib.cread(rootInode.indirect, indirect);
			
			if (errVal == 0)
			{
				for (int offset = 0; offset < Disk.blockSize; offset += 4)
				{
					if (SysLib.bytes2int(indirect, offset) <= DIR_INODE)
						break;
						
					blockCount++;
					indirectCount++;
				}
			}
			else
				return null;
		}
		int blockIndex = 0;
		byte[] dirData = new byte[blockCount * Disk.blockSize];
		for (int i = 0; i < rootInode.direct.length && rootInode.direct[i] > DIR_INODE; i++)
		{
			errVal = SysLib.cread(rootInode.direct[i], blockData);
			
			if (errVal < SUCCESS)
				return null;
				
			System.arraycopy(blockData, 0, 
					  dirData, blockIndex * Disk.blockSize, 
					  Disk.blockSize);
					  
			blockIndex++;
		}
		
		if (indirect != null)
		{
			for (int i = 0; i < indirectCount; i++)
			{
                            errVal = SysLib.cread(indirect[SysLib.bytes2int(indirect, i * 4)], blockData);
				
				if (errVal < SUCCESS)
					return null;
					
				System.arraycopy(blockData, 0, 
						  dirData, blockIndex * Disk.blockSize, 
						  Disk.blockSize);
						  
				blockIndex++;
			}

                            if (errVal < SUCCESS)
                                    return null;

                            System.arraycopy(blockData, 0, 
                                              dirData, blockIndex * Disk.blockSize, 
                                              Disk.blockSize);

                            blockIndex++;
		}
		
                return dirData;
	}
}
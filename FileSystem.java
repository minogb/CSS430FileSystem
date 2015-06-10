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
		
		if (entry.seekPtr >= entry.inode.length || !(entry.mode == "r" || entry.mode == "w+")) // Verify the right mode
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
			SysLib.cerr("line #275\n");
		
			indirectData = new byte[Disk.blockSize];
			errVal = SysLib.cread(entry.inode.indirect, indirectData);
			
			if (errVal != SUCCESS)
			{
				entry.inode.finishRead();
				return ERROR;
			}
			
			SysLib.cerr("line #286\n");
			SysLib.cerr(((blockNum - entry.inode.direct.length) * 2) + "\n");
			
				
			errVal = SysLib.cread(SysLib.bytes2short(indirectData, (blockNum - entry.inode.direct.length) * 2), blockData);
                        blockNum++;
			
			if (errVal != SUCCESS)
			{
				entry.inode.finishRead();
				return ERROR;
			}
			
			SysLib.cerr("line #297\n");
		}
		else // Are we starting the reads at the direct connected blocks
		{
			SysLib.cerr("line #301\n");
			
			if (blockNum + blockCount >= entry.inode.direct.length) // If we will be accessing the indirect block, load it now
			{
				SysLib.cerr("line #305\n");
			
				indirectData = new byte[Disk.blockSize];
				errVal = SysLib.cread(entry.inode.indirect, indirectData);
				
				if (errVal != SUCCESS)
				{
					entry.inode.finishRead();
					return ERROR;
				}
				
				SysLib.cerr("line #316\n");
			}
		
			SysLib.cerr("line #319\n");
		
			errVal = SysLib.cread(entry.inode.direct[blockNum++], blockData);
			
			if (errVal != SUCCESS)
			{
				entry.inode.finishRead();
				return ERROR;
			}
			
			SysLib.cerr("line #329\n");
		}
		
		SysLib.cerr("line #332\n");
		
		if (blockCount == 1) // Most simple version for small reads
		{
			SysLib.cerr("line #336\n");
		
			System.arraycopy(blockData, blockOffset, 
					  buffer, 0, 
					  readSize);
					  
			entry.seekPtr += readSize;
		}
		else
		{
			SysLib.cerr("line #346\n");
		
			int bufferOffset = Disk.blockSize - blockOffset; 
			System.arraycopy(blockData, blockOffset, 
					  buffer, 0, 
					  bufferOffset);
		
			for (; blockNum < blockCount; blockNum++)
			{
				SysLib.cerr("line #355\n");
			
				// Bound the individual block read size by the size of the block itself
				int blockReadSize = (readSize - bufferOffset > Disk.blockSize) ? 
					Disk.blockSize : readSize - bufferOffset;
			
				if (blockNum >= entry.inode.direct.length) // Are we accessing an indirect block
				{
					SysLib.cerr("line #363\n");
				
					errVal = SysLib.cread(SysLib.bytes2short(indirectData, 
                                                (blockNum - entry.inode.direct.length) * 2),
                                                blockData);
				}
				else // Nope, we are a direct block
				{
					SysLib.cerr("line #371\n");
				
					errVal = SysLib.cread(entry.inode.direct[blockNum], blockData);
				}
				
				SysLib.cerr("line #376\n");
					
				if (errVal != SUCCESS)
				{
					entry.inode.finishRead();
					return ERROR;
				}
				
				SysLib.cerr("line #384\n");
					
				System.arraycopy(blockData, 0,
					buffer, bufferOffset,
					blockReadSize);
						
				entry.seekPtr += blockReadSize;
				
				bufferOffset += blockReadSize;
			}
			
			SysLib.cerr("line #395\n");
		}
		
		entry.inode.finishRead();
				
		SysLib.cerr("line #400\n");
		
		return readSize;
	}
	
	public int write(int fd, byte[] buffer)
	{
		SysLib.cerr("line #369\n");
	
		if (fd < 3 || buffer == null)
			return ERROR;
	
		TCB tcb = scheduler.getMyTcb();
		if (tcb == null || fd >= tcb.ftEnt.length || tcb.ftEnt[fd] == null)
			return ERROR;
		
		SysLib.cerr("line #378\n");
			
		FileTableEntry entry = tcb.ftEnt[fd];
		
		if (!(entry.mode == "w" || entry.mode == "w+" || entry.mode == "a")) // Verify the right mode
			return ERROR;
		
		SysLib.cerr("line #385\n");
			
		int errVal = SUCCESS;
		
		entry.inode.waitWrite(); // Blocking until the current write operation is finished, if any
		
		int blockNum = entry.seekPtr / Disk.blockSize; // Int division truncates remainder
		int blockOffset = entry.seekPtr % Disk.blockSize; // Remainder is the first block offset
		SysLib.cerr("Block offset: " + blockOffset + "\n");
		int writeSize = (entry.seekPtr + buffer.length > Inode.MAX_FILE_SIZE) ? // Bound the writeSize by the size of the file itself
			Inode.MAX_FILE_SIZE - entry.seekPtr : 
			buffer.length;
		int blockCount = (blockOffset + writeSize) / Disk.blockSize + 1; // Calculate how many blocks will be read (always at least 1)
		short blockIndex = -1;
		
		byte[] blockData = new byte[Disk.blockSize];
		byte[] indirectData = null;
		if (blockNum >= entry.inode.direct.length) // Are we referencing an indirect blockCount
		{
			if (entry.inode.indirect == -1)
				entry.inode.indirect = superBlock.getNextFreeBlock();
				
			SysLib.cerr("line #406\n");
			
			if (entry.inode.indirect == -1)
			{
				entry.inode.finishWrite();
				return ERROR;
			}
			
			SysLib.cerr("line #414\n");
		
			indirectData = new byte[Disk.blockSize];
			errVal = SysLib.cread(entry.inode.indirect, indirectData);
			
			if (errVal != SUCCESS)
			{
				entry.inode.finishWrite();
				return ERROR;
			}
			
			SysLib.cerr("line #425\n");
			
			short indirectIndex = (short) ((blockNum - entry.inode.direct.length) * 2);
			short indirectBlock = SysLib.bytes2short(indirectData, indirectIndex);
			if (indirectBlock == 0)
			{
				SysLib.cerr("line #431\n");
				
				indirectBlock = superBlock.getNextFreeBlock();
				
				if (indirectBlock == ERROR)
				{
					entry.inode.finishWrite();
					return ERROR;
				}
				SysLib.cerr("line #440\n");
				
				SysLib.short2bytes(indirectBlock, indirectData, indirectIndex);
				
				errVal = SysLib.cwrite(indirectBlock, indirectData);
				
				if (errVal != SUCCESS)
				{
					entry.inode.finishWrite();
					return ERROR;
				}
				SysLib.cerr("line #451\n");
			}
			
			errVal = SysLib.cread(indirectBlock, blockData);
			
			if (errVal != SUCCESS)
			{
				entry.inode.finishWrite();
				return ERROR;
			}
			
			SysLib.cerr("line #462\n");
			
			blockIndex = indirectBlock;
		}
		else
		{
			SysLib.cerr("line #468\n");
			if (blockNum + blockCount >= entry.inode.direct.length)
			{
				SysLib.cerr("line #471\n");
				if (entry.inode.indirect == -1)
					entry.inode.indirect = superBlock.getNextFreeBlock();
					
				if (entry.inode.indirect == -1)
				{
					entry.inode.finishWrite();
					return ERROR;
				}
				
				SysLib.cerr("line #481\n");
			
				indirectData = new byte[Disk.blockSize];
				errVal = SysLib.cread(entry.inode.indirect, indirectData);
				
				if (errVal != SUCCESS)
				{
					entry.inode.finishWrite();
					return ERROR;
				}
				SysLib.cerr("line #491\n");
			}
		
			if (entry.inode.direct[blockNum] == -1)
				entry.inode.direct[blockNum] = superBlock.getNextFreeBlock();
				
			if (entry.inode.direct[blockNum] == -1)
			{
				entry.inode.finishWrite();
				return ERROR;
			}
			
			SysLib.cerr("line #503\n");
			
			errVal = SysLib.cread(entry.inode.direct[blockNum], blockData);
			
			if (errVal != SUCCESS)
			{
				entry.inode.finishWrite();
				return ERROR;
			}
			
			SysLib.cerr("line #513\n");
			
			blockIndex = entry.inode.direct[blockNum];
		}
		
		SysLib.cerr("line #518\n");
		
		if (blockCount == 1) // Most simple version for small writes
		{
			SysLib.cerr("line #522\n");
		
			System.arraycopy(buffer, 0, 
					  blockData, blockOffset, 
					  writeSize);
					  
			errVal = SysLib.cwrite(blockIndex, blockData);
			
			if (errVal != SUCCESS)
			{
				entry.inode.finishWrite();
				return ERROR;
			}
			
			SysLib.cerr("line #536\n");
					  
			entry.seekPtr += writeSize;
			entry.inode.length += writeSize;
		}
		else
		{
			SysLib.cerr("line #543\n");
		
			int bufferOffset = Disk.blockSize - blockOffset; 
			System.arraycopy(buffer, 0, 
					  blockData, blockOffset, 
					  bufferOffset);
					  
			errVal = SysLib.cwrite(blockIndex, blockData);
			
			if (errVal != SUCCESS)
			{
				entry.inode.finishWrite();
				return ERROR;
			}
						
			SysLib.cerr("BEFORE: seekPtr: " + entry.seekPtr + ", inode.length: " + entry.inode.length + "\n");
			entry.seekPtr += bufferOffset;
			entry.inode.length += bufferOffset;
			SysLib.cerr("AFTER:  seekPtr: " + entry.seekPtr + ", inode.length: " + entry.inode.length + "\n");
		
			for (blockNum++; blockNum < blockCount; blockNum++)
			{
				SysLib.cerr("line #552\n");
			
				// Bound the individual block read size by the size of the block itself
				int blockWriteSize = (writeSize - bufferOffset > Disk.blockSize) ? 
					Disk.blockSize : writeSize - bufferOffset;
			
				if (blockNum >= entry.inode.direct.length) // Are we accessing an indirect block
				{
					SysLib.cerr("line #560\n");
				
					short indirectIndex = (short) ((blockNum - entry.inode.direct.length) * 2);
					blockIndex = SysLib.bytes2short(indirectData, indirectIndex);
					if (blockIndex == 0)
					{
						SysLib.cerr("line #566\n");
					
						blockIndex = superBlock.getNextFreeBlock();
					
						if (blockIndex == ERROR)
						{
							entry.inode.finishWrite();
							return ERROR;
						}
						
						SysLib.cerr("line #576\n");
						
						SysLib.short2bytes(blockIndex, indirectData, indirectIndex);
						
						errVal = SysLib.cwrite(entry.inode.indirect, indirectData);
						
						if (errVal != SUCCESS)
						{
							entry.inode.finishWrite();
							return ERROR;
						}
						
						SysLib.cerr("line #588\n");
					}
				}
				else // Nope, we are a direct block
				{
					SysLib.cerr("line #593\n");
					
					blockIndex = entry.inode.direct[blockNum];
					if (blockIndex == -1)
					{
						SysLib.cerr("line #598\n");
					
						blockIndex = superBlock.getNextFreeBlock();
				
						if (blockIndex == -1)
						{
							entry.inode.finishWrite();
							return ERROR;
						}
						
						SysLib.cerr("line #608\n");
						
						entry.inode.direct[blockNum] = blockIndex;
					}
				}
					
				SysLib.cerr("line #614\n");
					
				errVal = SysLib.cread(blockIndex, blockData);
					
				if (errVal != SUCCESS)
				{
					entry.inode.finishWrite();
					return ERROR;
				}
				
				SysLib.cerr("line #624\n");
					
				System.arraycopy(buffer, bufferOffset,
					blockData, 0,
					blockWriteSize);
					
				errVal = SysLib.cwrite(blockIndex, blockData);
					
				if (errVal != SUCCESS)
				{
					entry.inode.finishWrite();
					return ERROR;
				}
				
				SysLib.cerr("line #638\n");
						
				entry.seekPtr += blockWriteSize;
				entry.inode.length += blockWriteSize;
				
				bufferOffset += blockWriteSize;
			}
			
			SysLib.cerr("line #646\n");
		}
		
		entry.inode.finishWrite();
		
		SysLib.cerr("line #651\n");
				  
		return writeSize;
	}
	private boolean readHelperForWrite(byte[] returnValue, int blockNum, Inode inode)
        {
            if(returnValue.length < 1)
                return false;
            byte[] temp = new byte[Disk.blockSize];
            if(blockNum < 0)
                return false;
            if(blockNum < 11)
            {
                blockNum = inode.direct[blockNum];
            }
            else
            {
                SysLib.cread(inode.indirect,temp);
                blockNum = SysLib.bytes2short(temp, 2*(blockNum-11));
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
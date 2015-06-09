import java.util.*;

public class FileSystem
{
	//Interface Variables
	public Disk disk;
	public Scheduler scheduler; // Only for getMyTCB

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

	public FileSystem(Disk _disk, Schedulder _scheduler)
	{
		disk = _disk;
		scheduler = _scheduler;
		
		
		fileCount = DEFAULT_FORMAT;
		
		dir = new Directory(DEFAULT_FORMAT);
		byte[] dirData = getDiskDirData();
		dir.bytes2directory(dirData);
		
		fileTable = new FileTable(dir);
	}
	
	public int open(String filename, String mode)
	{
		if (!(mode == "r" || mode == "w" || mode == "w+" || mode == "a"))
			return ERROR;
			
		TCB tcb = scheduler.getMyTCB();
		if (tcb == null)
			return ERROR;
			
		int fd = 0;
		for (;fd < tcb.ftEnt.length; fd++)
			if (tcb.ftEnt[fd] == null)
				break;
				
		if (fd >= tcb.ftEnt.length)
			return ERROR;
			
		FileTableEntry entry = fileTable.falloc(filename, mode);
		if (entry == null)
			return ERROR;
			
		tcb.ftEnt[fd] = entry;
		
		return fd;
	}
	
	public int close(int fd)
	{
            if (fd < 3)
            {
                    return ERROR;
            }
            TCB tcb = scheduler.getMyTCB();
            if (tcb == null || fd >= tcb.ftEnt.length || tcb.ftEnt[fd] == null)
                return ERROR;

            if(--tcb.ftEnt[fd].inode.count < 1 && tcb.ftEnt[fd].inode.flag == Inode.MARKED_FOR_DEATH)
            {
                if(!fileTable.ffree(tcb.ftEnt[fd]))
                {
                    return ERROR;
                }
                return delete(tcb.ftEnt[fd].iNumber);
            }
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
	
		TCB tcb = scheduler.getMyTCB();
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
		
		inode.seekPtr = offset;
			
		return 0;
	}
	private int delete(int inumber)
        {
            String fileName = dir.iname((short)inumber);
            for(int i = 0; i < dir.fsize.length;i++)
            {
                if(fileName.equals(dir.fnames[inumber].toString()))
                {
                    //delete the reference in the directory
                    dir.fsize[i] = 0;
                    dir.fnames[i] = "".toCharArray();
                    //delete the refernce in the file table
                    for(int j = 0; j < fileTable.table.get(inumber).direct.length; j++)
                    {
                        SuperBlock.returnBlock(fileTable.table.get(inumber).direct[j]);
                        SuperBlock.returnBlock((short) (inumber / SuperBlock.iNodesPerBlock + 1));
                    }
                    byte[] block = new byte[Disk.blockSize];
                    Syslib.cread(block, entry.inode.indirect);
                    //delete all indirect
                    for(int j =0; j < block.length; j+=2)
                    {
                        short pointedBlock = SysLib.bytes2short(block, j);
                        if(pointedBlock < 0)
                            break;
                        SuperBLock.returnBlock(pointedBlock);
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
            return delete(fileTable.namei(filename));
	}
	
	public int read(int fd, byte[] buffer)
	{
		if (fd < 3 || buffer == null)
			return ERROR;
	
		TCB tcb = scheduler.getMyTCB();
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
		
		int[] blockData = new int[Disk.blockSize];
		byte[] indirectData = null;
		if (blockNum >= entry.inode.direct.length) // Are we referencing an indirect blockCount
		{
			indirectData = new byte[Disk.blockSize];
			errVal = Syslib.cread(indirectData, entry.inode.indirect);
			
			if (errVal != SUCCESS)
				return ERROR;
				
			errVal = Syslib.cread(
				blockData, // Destination
				Syslib.bytes2int(indirectData,
					(blockNum - entry.inode.direct.length) * 2)); // Grab the block index from the indirect block
					
			blockNum++;
			
			if (errVal != SUCCESS)
				return ERROR;
		}
		else // Are we starting the reads at the direct connected blocks
		{
			if (blockNum + blockCount >= entry.inode.direct.length) // If we will be accessing the indirect block, load it now
			{
				indirectData = new byte[Disk.blockSize];
				errVal = Syslib.cread(indirectData, entry.inode.indirect);
				
				if (errVal != SUCCESS)
					return ERROR;
			}
		
			errVal = Syslib.cread(blockData, entry.inode.direct[blockNum++]);
			
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
					errVal = Syslib.cread(blockData, 
						Syslib.bytes2int(indirectData,
							(blockNum - entry.inode.direct.length) * 4));
				}
				else // Nope, we are a direct block
					errVal = Syslib.cread(blockData, entry.inode.direct[blockNum]);
					
				if (errVal != SUCCESS)
					return ERROR;
					
				System.arraycopy(blockData, 0,
					buffer, bufferOffset,
					blockReadSize);
						
				entry.seekPtr += blockReadSize;
				
				bufferOffset += blockReadSize;
			}
		}
		
		entry.inode.finishRead()
				  
		return readSize;
	}
	
	public int write(int fd, byte[] buffer)
	{
                if (fd < 3 || buffer == null)
                    return ERROR;

                TCB tcb = scheduler.getMyTCB();
                if (tcb == null || fd >= tcb.ftEnt.length || tcb.ftEnt[fd] == null)
                    return ERROR;
                
		FileTableEntry entry = tcb.ftEnt[fd];
                int endPoint = buffer.length + entry.seekPtr;
                //starting block
                int dirPtr = entry.seekPtr / Disk.blockLength;
                //Check if we are in the bounds of writing to a single block
                if(endPoint < Disk.blockLength * (dirPtr+1))
                {
                    
                    //Direct?
                    if(dirPtr < 11)
                    {
                        //get the length of stored data
                        byte[] data = new byte[entry.seekPtr % dirPtr];
                        SysLib.rawread(data, entry.inode.direct[dirPtr]);
                        byte[] writeableData = new byte[data.length + buffer.length];
                        //append data
                        System.arraycopy(buffer, 0, writeableData, 0, buffer.length);
                        System.arraycopy(data, 0, writeableData, buffer.length, data.length);
                        //direct and only one block, so a simple write will do
                        SysLib.rawwrite(entry.inode.direct[dirPtr],writeableData);
                        //move the pointer ahead
                        entry.inode.seekPtr += buffer.length;
                        return buffer.length;
                    }
                    else
                    {

                        //indirect single block write
                        byte[] indirectBlock = new byte[Disk.blockLength];
                          short blockNm = -1;
			blockNm = SysLib.byte2short(indirectBlock, 2*(dirPtr-11));                      

                        //get the length of stored data
                        byte[] data = new byte[entry.seekPtr % dirPtr];
                        SysLib.rawread(data, blockNm);
                        byte[] writeableData = new byte[data.length + buffer.length];
                        //append data
                        System.arraycopy(buffer, 0, writeableData, 0, buffer.length);
                        System.arraycopy(data, 0, writeableData, buffer.length, data.length);
                        //direct and only one block, so a simple write will do
                        SysLib.rawwrite(blockNm,writeableData);
                        //move the pointer ahead
                        entry.inode.seekPtr += buffer.length;
                        return buffer.length;
                    }
                }
                else
                {
                    //multiblock write
                    int numWriten = 0;
                    for(int i = dirPtr; i < endPoint * Disk.blockLength; i++)
                    {
                        //direct?
                        if(i < 12)
                        {
                        }
                        else//inderect
                        {
                            
                        }
                    }
                }
		return ERROR;
	}
	
	public int size(int fd)
	{
		if (fd < 3)
			return ERROR;
	
		TCB tcb = scheduler.getMyTCB();
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
		Inode rootInode = new Inode(DIR_INODE); 
		
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
			errVal = Syslib.cread(rootInode.indirect, indirect);
			
			if (errVal == 0)
			{
				for (int offset = 0; offset < Disk.blockSize; offset += 4)
				{
					if (Syslib.bytes2int(indirect, offset) <= DIR_INODE)
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
			errVal = Syslib.cread(blockData, rootInode.direct[i]);
			
			if (errVal < SUCCESS)
				return null;
				
			System.arraycopy(blockData, 0, 
					  dirData, blockIndex * Disk.blockSize, 
					  blockSize);
					  
			blockIndex++;
		}
		
		if (indirect != null)
		{
			for (int i = 0; i < indirectCount; i++)
			{
				errVal = Syslib.cread(blockData, indirect[Syslib.bytes2int(indirect, i * 4)]);
				
				if (errVal < SUCCESS)
					return null;
					
				System.arraycopy(blockData, 0, 
						  dirData, blockIndex * Disk.blockSize, 
						  Disk.blockSize);
						  
				blockIndex++;
			}
		}
	}
}
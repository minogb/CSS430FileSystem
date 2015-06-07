import java.util.*;

public class FileSystem
{
	//Interface Variables
	public Disk disk;
	public Scheduler scheduler; // Only for getMyTCB
	
	public static final int DEFAULT_FORMAT = 48;
	public static final int SEEK_SET = 0;
	public static final int SEEK_CUR = 1;
	public static final int SEEK_END = 2;

	//Instance Variables
	private SuperBlock superBlock;
	private Directory dir;
	private FileTable fileTable;
	
	
	private int fileCount;
	
	private static final int DIR_INODE = 0;
	
	private static final int SUCCCESS = 0;
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
		if (!(mode == "r" || mode == "w" || mode == "w+" || 
                        mode == "a"))
                {
                    return ERROR;
                }
		TCB tcb = scheduler.getMyTCB();
		if (tcb == null)
                {
                    return ERROR;
                }
		int fd = 0;
		for (;fd < tcb.ftEnt.length; fd++)
                {
                    if (tcb.ftEnt[fd] == null)
                    {
                        break;
                    }
                }
		if (fd >= tcb.ftEnt.length)
                {
			return ERROR;
                }
		FileTableEntry entry = fileTable.falloc(filename, mode);
		if (entry == null)
                {
                    return ERROR;
                }
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
			
		int errVal = SUCCCESS;
		
		int blockNum = entry.seekPtr / Disk.blockSize;
		int blockOffset = entry.seekPtr % Disk.blockSize;
		int readSize = (entry.seekPtr + buffer.length > entry.inode.length) ? 
			entry.inode.length - entry.seekPtr : 
			buffer.length;
		int blockCount = (blockOffset + readSize) / Disk.blockSize + 1;
		
		int blockData = new blockData[Disk.blockSize];
		byte[] indirectData = null;
		if (blockNum >= entry.inode.direct.length) // Are we referencing an indirect blockCount
		{
			indirectData = new blockData[Disk.blockSize]
			errVal = Syslib.cread(indirectData, entry.inode.indirect);
			
			if (errVal != SUCCCESS)
				return ERROR;
				
			errVal = Syslib.cread(
				blockData, // Destination
				Syslib.bytes2int(indirectData,
					(blockNum - entry.inode.direct.length) * 4)); // Grab the block index from the indirect block
					
			blockNum++;
			
			if (errVal != SUCCCESS)
				return ERROR;
		}
		else
		{
			if (blockNum + blockCount >= entry.inode.direct.length)
			{
				indirectData = new blockData[Disk.blockSize]
				errVal = Syslib.cread(indirectData, entry.inode.indirect);
				
				if (errVal != SUCCCESS)
					return ERROR;
			}
		
			errVal = Syslib.cread(blockData, direct[blockNum++]);
			
			if (errVal != SUCCCESS)
				return ERROR;
		}
		
		if (blockCount == 1)
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
				int blockReadSize = (readSize - bufferOffset > Disk.blockSize) ? Disk.blockSize : readSize - bufferOffset;
			
				if (blockNum >= entry.inode.direct.length)
				{
					errVal = Syslib.cread(blockData, 
						Syslib.bytes2int(indirectData,
							(blockNum - entry.inode.direct.length) * 4));
				}
				else
					errVal = Syslib.cread(blockData, direct[blockNum]);
					
				if (errVal != SUCCCESS)
					return ERROR;
					
				System.arraycopy(blockData, 0,
					buffer, bufferOffset,
					blockReadSize);
						
				entry.seekPtr += blockReadSize;
				
				bufferOffset += blockReadSize;
			}
		}
				  
		return readSize;
	}
	
	public int write(int fd, byte[] buffer)
	{
		return ERROR;
	}
	
	public int size(int fd)
	{
		return ERROR;
	}
	
	public int format(int fileCount)
	{
		return ERROR;
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
		byte[] dirData = new byte[blockCount * Disk.blockSize);
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
			
			errVal = Syslib.cread(blockData, indirect[Syslib.bytes2int(indirect, i * 4)]);
			
			if (errVal < SUCCESS)
				return null;
				
			System.arraycopy(blockData, 0, 
					  dirData, blockIndex * Disk.blockSize, 
					  blockSize);
					  
			blockIndex++;
		}
	}
}
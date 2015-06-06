import java.util.Vector;

public class FileTable
{
	private Vector table
	private Directory dir;
	
	public FileTable(Directory _dir)
	{
		table = new Vector();
		dir = _dir;
	}
	
	public synchronized FileTableEntry falloc(String filename, String mode)
	{
		bool isNewEntry = false;
			
		Inode inode;
		
		int iNumber = dir.namei(filename);
		if (iNumber == -1) // If the inode does not exist in the filesystem
		{
			iNumber = dir.ialloc(filename);
			
			if (iNumber == -1) // If inode allocation failed
				return null;
				
			Inode inode = new Inode(iNumber);
			inode.count = 1;
			
			isNewEntry = true;
		}
		else // If the directory knows about the file
		{
			int tableIndex = -1;
			for (int i = 0; i < table.size(); i++)
			{
				if (table[i].iNumber == iNumber)
				{
					tableIndex = i;
					break;
				}
			}
			
			if (tableIndex == -1) // If the file has not been opened yet
			{
				Inode inode = new Inode(iNumber);
				inode.count = 1;
				
				isNewEntry = true;
			}
			else // If the file is opened and we store a reference of it in our table
			{
				Inode inode = table[i].inode;
                                //Check to see if the file is marked to prevent opening
                                if(inode.flag == Inode.MARKED_FOR_DEATH)
                                {
                                    return null;
                                }
				inode.count++;
			}
		}
		
		inode.toDisk(iNumber);
				
		FileTableEntry entry = new FileTableEntry(inode, iNumber, mode);
		entry.count = 1;
		
		if (isNewEntry)
			table.add(entry);
			
		return entry;
	}
	
	public synchronized boolean ffree(FileTableEntry e)
	{
		int tableIndex = -1;
		for (int i = 0; i < table.size(); i++)
		{
			if (table[i].iNumber == e.iNumber)
			{
				tableIndex = i;
				break;
			}
		}
		
		if (tableIndex == -1) // If the FileTableEntry was not created by us
			return false;
			
		FileTableEntry entry = table[tableIndex];
		entry.count--;
		entry.inode.count--;
			
		if (entry.inode.count == 0)
			table.remove(tableIndex);
			
		entry.inode.toDisk(entry.iNumber);
		
		return true;
	}
	
	public synchronized boolean fempty()
	{
		return table.empty();
	}
}
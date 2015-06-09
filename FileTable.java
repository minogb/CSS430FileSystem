
import java.util.Vector;


public class FileTable
{
	private Vector<FileTableEntry> table;
	private Directory dir;
	
	public FileTable(Directory _dir)
	{
		table = new Vector<FileTableEntry>();
		dir = _dir;
	}
	
	public FileTableEntry falloc(String filename, String mode)
	{
		boolean isNewEntry = false;
			
		Inode inode = null;
		short iNumber = -1;
		while (true)
		{
			iNumber = dir.namei(filename);
			
			if (iNumber == -1) // If the inode does not exist in the filesystem
			{
				iNumber = dir.ialloc(filename);
				
				// THIS IS BAD DESIGN AT THIS POINT
				// The reason is that if two threads, at exactly the same time,
				// create a file with the same name, one will succeed and continue
				// on as normal, while the other will fail and return null.
				// The second thread SHOULD return the entry associated with the
				// file name that was just created, but it doesn't.
				
				if (iNumber == -1) // If inode allocation failed
					return null;
					
				Inode inode = new Inode();
				inode.count = 1;
				
				inode.waitWrite();
				
				isNewEntry = true;
			}
			else // If the directory knows about the file
			{
				int tableIndex = -1;
				for (int i = 0; i < table.size(); i++)
				{
				if (table.get(tableIndex).iNumber == iNumber)
					{
						tableIndex = i;
						break;
					}
				}
				
				Inode inode = null;
				if (tableIndex == -1) // If the file has not been opened yet
					inode = new Inode(iNumber);
				else // If the file is opened and we store a reference of it in our table
					inode = table.get(tableIndex).inode;
				
				if (inode.isDying())
					return null;
				
				inode.waitWrite();
				
				inode.count++;
			}
		}
		
		inode.toDisk(iNumber);
				
		FileTableEntry entry = new FileTableEntry(inode, iNumber, mode);
		entry.count = 1;
		
		if (isNewEntry)
			table.add(entry);
			
		inode.finishWrite();
			
		return entry;
	}
	
	public boolean ffree(FileTableEntry e)
	{
		int tableIndex = -1;
		for (int i = 0; i < table.size(); i++)
		{
			if (table.get(tableIndex).iNumber == e.iNumber)
			{
				tableIndex = i;
				break;
			}
		}
		
		if (tableIndex == -1) // If the FileTableEntry was not created by us
			return false;
		FileTableEntry entry = table.get(tableIndex);
		
		entry.inode.waitWrite();
		
		entry.count--;
		entry.inode.count--;
		if (entry.inode.count == 0)
			table.removeElementAt(tableIndex);
			
		entry.inode.toDisk(entry.iNumber);
		
		entry.inode.finishWrite();
		
		return true;
	}
	
	public synchronized boolean fempty()
	{
		return table.empty();
	}
}
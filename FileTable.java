
import java.util.Vector;


public class FileTable
{
	public Vector<FileTableEntry> table;
	public Directory dir;
	
	public FileTable(Directory _dir)
	{
		table = new Vector<FileTableEntry>();
		dir = _dir;
	}
	
	public FileTableEntry falloc(String filename, String mode)
	{
		SysLib.cerr("FileTableEntry falloc(String filename, String mode)\n");
		boolean isNewEntry = false;
			
		Inode inode = null;
		short iNumber = -1;
		iNumber = dir.namei(filename);

		if (iNumber == -1) // If the inode does not exist in the filesystem
		{
			SysLib.cerr("  " + filename + " does not exist in the file system.\n");
			
			iNumber = dir.ialloc(filename);
			
			SysLib.cerr("  File: " + filename + ", iNumber: " + iNumber + "\n");

			// THIS IS BAD DESIGN AT THIS POINT
			// The reason is that if two threads, at exactly the same time,
			// create a file with the same name, one will succeed and continue
			// on as normal, while the other will fail and return null.
			// The second thread SHOULD return the entry associated with the
			// file name that was just created, but it doesn't.

			if (iNumber == -1) // If inode allocation failed
				return null;

			inode = new Inode();
			inode.count = 1;

			SysLib.cerr("  Waiting to write...\n");
			inode.waitWrite();
			SysLib.cerr("  Can write to inode " + iNumber + "\n");

			isNewEntry = true;
		}
		else // If the directory knows about the file
		{
			SysLib.cerr("  We know about this file!\n");
			SysLib.cerr("  File: " + filename + ", iNumber: " + iNumber + "\n");

		
			int tableIndex = -1;
			for (int i = 0; i < table.size(); i++)
			{
				if (table.get(tableIndex).iNumber == iNumber)
				{
						tableIndex = i;
						break;
				}
			}

			if (tableIndex == -1) // If the file has not been opened yet
				inode = new Inode(iNumber);
			else // If the file is opened and we store a reference of it in our table
				inode = table.get(tableIndex).inode;

			SysLib.cerr("  Checking to see if the file is dying...\n");
			if (inode.isDying())
				return null;

			SysLib.cerr("  Not dying! And Waiting to write...\n");
			inode.waitWrite();
			SysLib.cerr("  Can write to inode " + iNumber + "\n");

			inode.count++;
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
			if (table.get(i).iNumber == e.iNumber)
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
		return table.isEmpty();
	}
}
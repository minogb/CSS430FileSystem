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
	}
	
	public synchronized boolean ffree(FileTableEntry e)
	{
	}
	
	public synchronized boolean fempty()
	{
		return table.empty();
	}
}
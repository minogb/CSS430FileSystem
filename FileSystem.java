import java.util.*;

public class FileSystem
{
	//Interface Variables
	public Disk disk;

	//Instance Variables
	private SuperBlock superBlock;

	public FileSystem()
	{
	}
	
	public int open(String filename, String mode)
	{
		return -1;
	}
	
	public int read(int fd, byte[] buffer)
	{
		return -1;
	}
	
	public int write(int fd, byte[] buffer)
	{
		return -1;
	}
	
	public int seek(int fd, int offset, int whence)
	{
		return -1;
	}
	
	public int close(int fd)
	{
		return -1;
	}
	
	public int delete(String filename)
	{
		return -1;
	}
	
	public int size(int fd)
	{
		return -1;
	}
}
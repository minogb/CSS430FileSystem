public class Directory
{
	private static int maxChars = 30; // max characters of each file name
 
	// Directory entries
	private int fsize[];        // each element stores a different file name size.
	private char fnames[][];    // each element stores a different file name.
 
	public Directory( int maxInumber ) { // directory constructor
		fsize = new int[maxInumber];     // maxInumber = max files
		for ( int i = 0; i < maxInumber; i++ ) 
			fsize[i] = 0;                 // all file size initialized to 0
		fnames = new char[maxInumber][maxChars];
		String root = "/";                // entry(inode) 0 is "/"
		fsize[0] = root.length( );        // fsize[0] is the size of "/".
		root.getChars( 0, fsize[0], fnames[0], 0 ); // fnames[0] includes "/"
	}
 
	public int bytes2directory( byte data[] )
	{
		if (data == null)
			return -1;
	
		int offset = 0;
		int maxInumber = Syslib.bytes2int(data, offset);
		if (maxInumber < 1)
			return -1;
		
		offset = 4;
		
		fsize = new int[maxInumber];
		fnames = new char[maxInumber][maxChars];
		
		int iNumber = 0;
		while (iNumber < maxInumber)
		{
			fsize[iNumber] = Syslib.bytes2int(data[offset], offset):
			offset += 4;
			
			for (int i = 0; i < fsize[iNumber]; i++)
				fnames[iNumber][i] = data[offset++];
				
			iNumber++;
		}
		
		return maxInumber;
	}
 
	public byte[] directory2bytes( )
	{
		int totalFSize = 0;
		for (int i = 0; i < fsize.length; i++)
			totalFSize += fsize[i];
			
		byte[] serialized = new byte[4 + fsize.length * 4 + totalFSize];
		
		int index = 0;
		
		Syslib.int2bytes(fsize.length, serialized, index);
		index = 4;
		
		for (int i = 0; i < fsize.length; i++)
		{
			Syslib.int2bytes(fsize[i], serialized, index);
			index += 4;
		
			for (int j = 0; j < fsize[i]; j++)
				serialized[index++] = fnames[i][j];
			
		}
		
		return serialized;
	}
 
	public short ialloc(String filename)
	{
		if (filename.length() > maxChars)
			return -1;
		
		short iNumber = -1;
		short freeINumber= -1;
		
		// Find the first free space or determine if the file exists
		for (iNumber = 0; iNumber < fsize.length; iNumber++)
		{
			if (fnames[iNumber] == null && freeINumber == -1)
				freeINumber = iNumber;
				
			if (fnames[iNumber] == filename)
				break;
		}
		
		// If no free space was found or the file already exists
		if (freeINumber == -1 || iNumber < fsize.length)
			return -1;
			
		fsize[freeINumber] = filename.length();
		filename.getChars(0, fsize[freeINumber], fnames[freeINumber], 0);
		
		Inode inode(freeINumber + 1);
		inode.invalidate()
		inode.toDisk(freeINumber + 1);
	
		return freeINumber + 1;
	}
 
	public boolean ifree( short iNumber )
	{
		if (iNumber < 1 || iNumber > fsize.length || fnames[iNumber - 1] == null)
			return false;
		
		Inode inode = new Inode(iNumber);
		inode.invalidate();
		inode.toDisk(iNumber);
		
		fsize[iNumber - 1] = 0;
		fnames[iNumber - 1] = null;
		
		return true;
	}
 
	public short namei( String filename )
	{
		if (filename.length() > maxChars)
			return -1;
		
		short iNumber;
		for (iNumber = 0; iNumber < fsize.length; iNumber++)
		{
			if (filename.equals(fnames[iNumber].toString()))
				break;
		}
		
		return (short) ((iNumber < fsize.length) ? iNumber + 1 : -1);
	}
}
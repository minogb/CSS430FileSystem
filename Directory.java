public class Directory
{
	private static int maxChars = 30; // max characters of each file name
 
	// Directory entries
	private int fsize[];        // each element stores a different file name size. WHY AM I HERE??????
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
 
	public int bytes2directory( byte data[] ) {
		// assumes data[] received directory information from disk
		// initializes the Directory instance with this data[]
	}
 
	public byte[] directory2bytes( ) {
		// converts and return Directory information into a plain byte array
		// this byte array will be written back to disk
		// note: only meaningfull directory information should be converted
		// into bytes.
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
		
		Inode inode(freeINumber);
		inode.invalidate()
		inode.toDisk(freeINumber);
	
		return freeINumber;
	}
 
	public boolean ifree( short iNumber )
	{
		if (iNumber < 0 || iNumber >= fsize.length || fnames[iNumber] == null)
			return false;
		
		Inode inode = new Inode(iNumber);
		inode.invalidate();
		inode.toDisk(iNumber);
		
		fsize[iNumber] = 0;
		fnames[iNumber] = null;
		
		return true;
	}
 
	public short namei( String filename )
	{
		if (filename.length() > maxChars)
			return -1;
		
		short iNumber;
		for (iNumber = 0; iNumber < fsize.length;; iNumber++)
		{
			if (fnames[iNumber] == filename)
				break;
		}
		
		return (iNumber < fsize.length) ? iNumber : -1;
	}
}
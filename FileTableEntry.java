/**
 * Inodes should be kept track of in this way to prevent mismatching
 * seek ptrs, inumbers, counts and the mode they are opened in
 */
public class FileTableEntry {
    /**
     * A seek pointer to the point in the file we are reading/writing to
     */
    public int seekPtr;
    /**
     * the inode this file is using
     */
    public final Inode inode;
    /**
     * the number of the inode, keep consistency across places it is kept
     * track of
     */
    public final short iNumber;
    /**
     * the number of process with this file/inode open
     */
    public int count;          
    /**
     * the mode we are in. r is read, w is write
     * w+ is write and read, a is append
     * cannot be changed until closed then reopened
     */
    public final String mode;
    /**
     * Create the entry based on minimal information
     * @param i the actual node to store
     * @param inumber the number of the node
     * @param m the mode we are open in
     */
    FileTableEntry ( Inode i, short inumber, String m ) {
        //set the seekerptr to the start or the end of a file based on the mode
        //see definition of mode  above
	seekPtr = m.equalsIgnoreCase("a") ?  i.length : 0;
	inode = i;
        iNumber = inumber;     
        //there is someone with us open
        count = 1;
        mode = m;

    }
}

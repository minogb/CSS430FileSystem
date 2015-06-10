
import java.util.Vector;

/**
 * This class directly manages the directory and file table
 */
public class FileTable {
    /**
     * This is the table of file table entries (files aka inodes)
     */
    public Vector<FileTableEntry> table;
    /**
     * this is a reference to our active directory system
     */
    public Directory dir;
    /**
     * Creates a new file system
     * @param _dir base the file system on a preexisting directory
     */
    public FileTable(Directory _dir) {
        table = new Vector<FileTableEntry>();
        dir = _dir;
    }
    /**
     * allocate a new file in our system
     * @param filename the to be files name
     * @param mode the to be mode the file is created in
     * @return the inumber or error
     */
    public FileTableEntry falloc(String filename, String mode) {
        //var to keep track if the allocated file has already been allocated
        boolean isNewEntry = false;

        Inode inode = null;
        short iNumber = -1;
        //get the inumber from the file name
        iNumber = dir.namei(filename);

        if (iNumber == -1) // If the inode does not exist in the filesystem
        {
            //When creating a new file it must be open in a writeable mode
            //cannot read something does not exist
            if (mode == "r") {
                return null;
            }
            //allocate the new file, giving us the inumber
            iNumber = dir.ialloc(filename);

            // THIS IS BAD DESIGN AT THIS POINT
            // The reason is that if two threads, at exactly the same time,
            // create a file with the same name, one will succeed and continue
            // on as normal, while the other will fail and return null.
            // The second thread SHOULD return the entry associated with the
            // file name that was just created, but it doesn't.
            if (iNumber == -1) // If inode allocation failed
            {
                //before we leave check once more for existing file, for the case
                //mentioned above
                iNumber = dir.namei(filename);
                if(iNumber == -1)
                    return null;
            }
            inode = new Inode();
            //the inode will have the current process accessing it
            inode.count = 1;
            //hold off all ye waiting
            inode.waitWrite();

            isNewEntry = true;
        } else // If the directory knows about the file
        {
            int tableIndex = -1;
            //get the right inode reference for the preexisting file
            for (int i = 0; i < table.size(); i++) {
                if (table.get(i).iNumber == iNumber) {
                    tableIndex = i;
                    break;
                }
            }
            
            if (tableIndex == -1) // If the file has not been opened yet
            {
                //grab the inode from memory
                inode = new Inode(iNumber);
            } else // If the file is opened and we store a reference of it in our table
            {
                inode = table.get(tableIndex).inode;
            }
            //if the file has already been marked for deletion we cannot
            //open the file
            if (inode.isDying()) {
                return null;
            }
            //Your free to frolic
            inode.waitWrite();
            //update the number of people that have this file open
            inode.count++;
        }

        inode.toDisk(iNumber);
        //create a new file entry for external use
        FileTableEntry entry = new FileTableEntry(inode, iNumber, mode);
        entry.count = 1;

        if (isNewEntry) {
            table.add(entry);
        }

        inode.finishWrite();

        return entry;
    }
    /**
     * free an open entry
     * @param e to free
     * @return success/error
     */
    public boolean ffree(FileTableEntry e) {
        int tableIndex = -1;
        //get the table entry
        for (int i = 0; i < table.size(); i++) {
            if (table.get(i).iNumber == e.iNumber) {
                tableIndex = i;
                break;
            }
        }

        if (tableIndex == -1) // If the FileTableEntry was not created by us
        {
            return false;
        }
        FileTableEntry entry = table.get(tableIndex);
        //hold ye writers
        entry.inode.waitWrite();
        //remove a count
        entry.count--;
        entry.inode.count--;
        //remove us as we are the last reference
        if (entry.inode.count == 0) {
            table.removeElementAt(tableIndex);
        }
        //save changes to disk
        entry.inode.toDisk(entry.iNumber);
        //release all ye writers
        entry.inode.finishWrite();
        //WE DID IT BOYS!
        return true;
    }
    /**
     * empty the current file entries
     * @return success/fail
     */
    public synchronized boolean fempty() {
        return table.isEmpty();
    }
}

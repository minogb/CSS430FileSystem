
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileSystem {

    //Interface Variables
    /**
     * the disk we will be access to store and rip data from
     */
    public Disk disk;
    /**
     * This will give us the tcb of the current process
     */
    public Scheduler scheduler;
    /**
     * The block contaning and managing the blocks in the system
     */
    private SuperBlock superBlock;
    /**
     * The directory for the system, allows us to find inodes based on filename
     */
    private Directory dir;
    /**
     * The file table allows us to look up all inodes and manipulate them
     */
    private FileTable fileTable;
    /**
     * Number of total files (inodes) in the system
     */
    private int fileCount;
    /**
     * Static definitions for our seeker function
     * Seek set is from the begining of the file
     */
    public static final int SEEK_SET = 0;
    /**
     * Static definitions for our seeker function
     * Seek current is from the current seeker location
     */
    public static final int SEEK_CUR = 1;
    /**
     * Static definitions for our seeker function
     * Seek end is from the end of the file
     */
    public static final int SEEK_END = 2;

    /**
     * pointer to our directory node/block
     */
    private static final int DIR_INODE = 0;
    /**
     * static definition of a successful return
     */
    private static final int SUCCESS = 0;
    /**
     * static definition of an error
     */
    private static final int ERROR = -1;
    /**
     * Constructor for the file system disk and scheduler are stored for 
     * reference
     * @param _disk
     * @param _scheduler 
     */
    public FileSystem(Disk _disk, Scheduler _scheduler) 
    {
        disk = _disk;
        scheduler = _scheduler;
        superBlock = new SuperBlock(_disk.getDiskSize());

        fileCount = SuperBlock.DEFAULT_INODES;

        dir = new Directory(SuperBlock.DEFAULT_INODES);
        byte[] dirData = getDiskDirData();
        dir.bytes2directory(dirData);

        fileTable = new FileTable(dir);
    }
    /**
     * Opens any given file in a mode. If a file does not exist in mode r, a new 
     * file cannot be 
     * @param filename file to open
     * @param mode mode r, w, w+,a
     * @return fd or error
     */
    public int open(String filename, String mode) {
        //see if we are opening in a valid mode
        if (!(mode == "r" || mode == "w" || mode == "w+" || mode == "a")) {
            return ERROR;
        }
        //get the current proccesses tcb
        TCB tcb = scheduler.getMyTcb();
        if (tcb == null) {
            return ERROR;
        }
        //default fd is 3, as in, out, and error take the first 3
        int fd = 3;
        //find a valid fd, if 3 is taken
        for (; fd < tcb.ftEnt.length; fd++) {
            if (tcb.ftEnt[fd] == null) {
                break;
            }
        }
        //see if we have overloaded the number of files a proccess can have 
        //open
        if (fd >= tcb.ftEnt.length) {
            return ERROR;
        }
        //allocate room for the new open file
        FileTableEntry entry = fileTable.falloc(filename, mode);
        if (entry == null) {
            return ERROR;
        }
        //set the tcb entry
        tcb.ftEnt[fd] = entry;
        //return where we stuck the file entry
        return fd;
    }
    /**
     * Closes a file, if a file is marked for death, and this process is the
     * last file to close, delete it
     * @param fd to close
     * @return success/error
     */
    public synchronized int close(int fd) {
        //we cannot close std in,out or error, these are not managed by us
        if (fd < 3) {
            return ERROR;
        }
        //get the current proccess
        TCB tcb = scheduler.getMyTcb();
        //make sure everything is valid
        if (tcb == null || fd >= tcb.ftEnt.length || tcb.ftEnt[fd] == null) {
            return ERROR;
        }
        //check to see if the current proccess is marked for deletion
        if (--tcb.ftEnt[fd].inode.count < 1 && tcb.ftEnt[fd].inode.isDying()) {
            if (!fileTable.ffree(tcb.ftEnt[fd])) {
                return ERROR;
            }
            //delete current file, as we are the last to open it, thus we need 
            //to close it
            return delete(tcb.ftEnt[fd].iNumber);
        } else if (tcb.ftEnt[fd].inode.count < 1) {
            notify();
        }
        //remove the file reference from the local file descriptor
        if (fileTable.ffree(tcb.ftEnt[fd])) {
            tcb.ftEnt[fd] = null;
            return 0;
        } else {
            return ERROR;
        }
    }
    /**
     * move the seek pointer for an inode (file)
     * @param fd file to change the seek pointer
     * @param offset distance to change
     * @param whence from where? end, current, start?
     * @return where seek ends, or error
     */
    public int seek(int fd, int offset, int whence) {
        //we cannot change std in,out, or error
        if (fd < 3) {
            return ERROR;
        }
        //basic/fast way to check for wrong whence input
        switch (whence) {
            case SEEK_SET:
            case SEEK_CUR:
            case SEEK_END:
                break;
            default:
                return ERROR;
        }
        //get current proccess
        TCB tcb = scheduler.getMyTcb();
        //check validity
        if (tcb == null || fd >= tcb.ftEnt.length || tcb.ftEnt[fd] == null) {
            return ERROR;
        }
        //get the file reference
        FileTableEntry entry = tcb.ftEnt[fd];
        Inode inode = entry.inode;
        //switch on how they want us to change the seek ptr
        switch (whence) {
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
        //set and return the result
        entry.seekPtr = offset;
        return entry.seekPtr;
    }
    /**
     * Delete given file by using its inumber, used as a helper class for
     * both delete(filename) and close
     * @param inumber file to close
     * @return success/error
     */
    private synchronized int delete(int inumber) {
        //check for valid close inumber
        if (inumber < 1) {
            return -1;
        }
        Inode current = null;
        //find inode to delete
        for (int i = 0; i < fileTable.table.size(); i++) {
            if (fileTable.table.get(i).iNumber == inumber) {
                current = fileTable.table.get(i).inode;
                break;
            }
        }
        //if nothing found error
        if (current == null) {
            return ERROR;
        }
        //wait for everyone to close
        if (current.count > 0) {
            current.markForDeath();
            while (current.count > 0) {
                try {
                    wait();
                } catch (InterruptedException ex) {
                }
            }
        }
        //get file name to remove from directory
        String fileName = dir.iname((short) (inumber - 1));
        //loop untill we hit the right spot in the directory
        for (int i = 0; i < dir.fsize.length; i++) {
            if (Directory.compare(fileName, dir.fnames[i]) == 0) {
                //delete the reference in the directory
                dir.fsize[i] = 0;
                dir.fnames[i][0] = 0;

                //delete the refernce in the file table
                for (int j = 0; j < current.direct.length; j++) {
                    superBlock.returnBlock(current.direct[j]);
                }

                // Delete all of the indirect blocks
                if (current.indirect != -1) {
                    byte[] indirectData = new byte[Disk.blockSize];
                    if (SysLib.cread(current.indirect, indirectData) != SUCCESS) {
                        return ERROR;
                    }
                    //delete all indirect data
                    short indirectBlock = 0;
                    for (short indirectIndex = 0; indirectIndex < 256; indirectIndex++) {
                        indirectBlock = SysLib.bytes2short(indirectData, indirectIndex * 2);

                        if (indirectBlock <= 0) {
                            continue;
                        }
                        //return the indirect block
                        superBlock.returnBlock(indirectBlock);
                    }
                    // return the indirect pointer block
                    superBlock.returnBlock(current.indirect);
                }
                //remove the reference in the inode table
                fileTable.table.removeElementAt(inumber - 1);
                return SUCCESS;
            }
        }
        //return error
        return ERROR;
    }
    /**
     * delete file using filename
     * @param filename file to delete
     * @return success/error
     */
    public int delete(String filename) {
        //lookup filename
        return delete(dir.namei(filename));
    }
    /**
     * Read a file using an fd and buffer, at most the file can only read
     * up to its length that has been writen too
     * @param fd file
     * @param buffer write to
     * @return success/error
     */
    public int read(int fd, byte[] buffer) {
        //check for validity
        if (fd < 3 || buffer == null) {
            return ERROR;
        }
        //get current proccess
        TCB tcb = scheduler.getMyTcb();
        //check for validity
        if (tcb == null || fd >= tcb.ftEnt.length || tcb.ftEnt[fd] == null) {
            return ERROR;
        }
        //get file reference
        FileTableEntry entry = tcb.ftEnt[fd];
        //check for validity
        if (entry.seekPtr >= entry.inode.length || !(entry.mode == "r" || entry.mode == "w+")) // Verify the right mode
        {
            return ERROR;
        }
        //errrVal will be a value we repedily use to check for valid results from
        //system calls
        int errVal = SUCCESS;
        // Blocking until the current write operation is finished, if any
        entry.inode.waitRead(); 
         // Int division truncates remainder
        int blockNum = entry.seekPtr / Disk.blockSize;
        // Remainder is the first block offset
        int blockOffset = entry.seekPtr % Disk.blockSize; 
         // Bound the readSize by the size of the filse
        int readSize = (entry.seekPtr + buffer.length > entry.inode.length) ?
                entry.inode.length - entry.seekPtr
                : buffer.length;
        // Calculate how many blocks will be read (always at least 1)
        int blockCount = (blockOffset + readSize) / Disk.blockSize + 1; 
        int lastBlock = (entry.seekPtr + readSize) / Disk.blockSize;

        byte[] blockData = new byte[Disk.blockSize];
        //indirect data will be used for referencing our indirect locations
        //in the future
        byte[] indirectData = null;
        // Are we referencing an indirect blockCount
        if (blockNum >= entry.inode.direct.length) //indirect
        {
            indirectData = new byte[Disk.blockSize];
            //read our indirect pointers from disk
            errVal = SysLib.cread(entry.inode.indirect, indirectData);
            //Check for failed read
            if (errVal != SUCCESS) {
                entry.inode.finishRead();
                return ERROR;
            }
            //read the indirect data
            errVal = SysLib.cread(
                    //get the pointer to the indirect block that we need
                    SysLib.bytes2short(indirectData,
                        //offset
                        (blockNum - entry.inode.direct.length) * 2), 
                    //destination
                    blockData);
            //check to see if the read was a failure
            if (errVal != SUCCESS) {
                entry.inode.finishRead();
                return ERROR;
            }
            //we have read one of the blocks already so increment
            blockNum++;

        }
         // Are we starting the reads at the direct connected blocks
        else //direct
        {
            // If we will be accessing the indirect block, load it now
            if (blockNum + blockCount >= entry.inode.direct.length) 
            {
                
                indirectData = new byte[Disk.blockSize];
                errVal = SysLib.cread(entry.inode.indirect, indirectData);
                //failure to read indirect pointers
                if (errVal != SUCCESS) {
                    entry.inode.finishRead();
                    return ERROR;
                }
            }
            //read in block data
            errVal = SysLib.cread(entry.inode.direct[blockNum++], blockData);
            //failed read?
            if (errVal != SUCCESS) {
                entry.inode.finishRead();
                return ERROR;
            }
        }
        // Most simple version for small reads
        if (blockCount == 1) 
        {
            //copy in data from our read to our read to location
            System.arraycopy(blockData, blockOffset,
                    buffer, 0,
                    readSize);
            //increment the seekptr based on how much we read
            entry.seekPtr += readSize;
        }
        else //multiblock
        {
            //the location in the block we will be starting our read at
            int bufferOffset = Disk.blockSize - blockOffset;
            //copy read data to the return to location
            System.arraycopy(blockData, blockOffset,
                    buffer, 0,
                    bufferOffset);
            //loop threw all of the blocks we are going to read from
            for (; blockNum <= lastBlock; blockNum++) {

                // Bound the individual block read size by the size of the block itself
                int blockReadSize = (readSize - bufferOffset > Disk.blockSize)
                        ? Disk.blockSize : readSize - bufferOffset;
                // Are we accessing an indirect block
                if (blockNum >= entry.inode.direct.length) //yes
                {
                    //read from disk
                    errVal = SysLib.cread(
                            //read the pointer to the block
                            SysLib.bytes2short(indirectData,
                                    //offset to the pointer
                                    (blockNum - entry.inode.direct.length) * 2),
                            blockData);
                    //failed to read
                    if (errVal != SUCCESS) {
                        entry.inode.finishRead();
                        return ERROR;
                    }
                } else // Nope, we are a direct block
                {
                    //read from direct block
                    errVal = SysLib.cread(entry.inode.direct[blockNum], blockData);
                    //failed
                    if (errVal != SUCCESS) {
                        entry.inode.finishRead();
                        return ERROR;
                    }
                }
                //failed at life, liberity, and the persute of happyness
                if (errVal != SUCCESS) {
                    entry.inode.finishRead();
                    return ERROR;
                }
                //copy data to our return location
                System.arraycopy(blockData, 0,
                        buffer, bufferOffset,
                        blockReadSize);
                //increment based on how much we read
                entry.seekPtr += blockReadSize;
                bufferOffset += blockReadSize;
            }
        }
        //release the wolves!
        entry.inode.finishRead();
        //return the amount we read
        return readSize;
    }
    /**
     * Writes to a file from  the seek pointer, will overide data
     * @param fd file
     * @param buffer data to write to the file
     * @return success/error
     */
    public int write(int fd, byte[] buffer) {
        //check for validity
        if (fd < 3 || buffer == null) {
            return ERROR;
        }
        //get current proccess
        TCB tcb = scheduler.getMyTcb();
        //check for validity
        if (tcb == null || fd >= tcb.ftEnt.length || tcb.ftEnt[fd] == null) {
            return ERROR;
        }
        //get the file from the fd
        FileTableEntry entry = tcb.ftEnt[fd];
        //check for any valid write mode
        if (!(entry.mode == "w" || entry.mode == "w+" || entry.mode == "a"))
        {
            return ERROR;
        }
        //var used to check for errors in system calls
        int errVal = SUCCESS;
        // Blocking until the current write operation is finished, if any
        entry.inode.waitWrite();
        // Int division truncates remainder
        int blockNum = entry.seekPtr / Disk.blockSize; 
        // Remainder is the first block offset
        int blockOffset = entry.seekPtr % Disk.blockSize; 
        // Bound the writeSize by the size of the file itself
        int writeSize = (entry.seekPtr + buffer.length > Inode.MAX_FILE_SIZE) ? 
                Inode.MAX_FILE_SIZE - entry.seekPtr
                : buffer.length;
        // Calculate how many blocks will be read (always at least 1)
        int blockCount = (blockOffset + writeSize) / Disk.blockSize + 1;
        int lastBlock = (entry.seekPtr + writeSize) / Disk.blockSize;
        //current number of blocks read
        short blockIndex = -1;

        byte[] blockData = new byte[Disk.blockSize];
        byte[] indirectData = null;
         // Are we referencing an indirect blockCount
        if (blockNum >= entry.inode.direct.length)//indirect
        {
            //if we don't have anydirect pointers set a new block for 'em
            if (entry.inode.indirect == -1) {
                entry.inode.indirect = superBlock.getNextFreeBlock();
            }
            //if we can't create/load the indirect pointer block error
            if (entry.inode.indirect == -1) {
                entry.inode.finishWrite();
                return ERROR;
            }
            //load our indirect pointer block
            indirectData = new byte[Disk.blockSize];
            errVal = SysLib.cread(entry.inode.indirect, indirectData);
            //can't load, error
            if (errVal != SUCCESS) {
                entry.inode.finishWrite();
                return ERROR;
            }
            //the offset to the pointer of the indred block
            short indirectIndex = (short) ((blockNum - entry.inode.direct.length) * 2);
            //load the pointer to the indirect block
            short indirectBlock = SysLib.bytes2short(indirectData, indirectIndex);
            //if we grabed a formated block, get a new one
            if (indirectBlock == 0) {
                //get new block
                indirectBlock = superBlock.getNextFreeBlock();
                //error if we couldn't grab a new block
                if (indirectBlock == ERROR) {
                    entry.inode.finishWrite();
                    return ERROR;
                }
                //store pointer to block
                SysLib.short2bytes(indirectBlock, indirectData, indirectIndex);
                //save back to memory
                errVal = SysLib.cwrite(indirectBlock, indirectData);
                //failed to save back to memeory
                if (errVal != SUCCESS) {
                    entry.inode.finishWrite();
                    return ERROR;
                }
            }
            //read any latent data in block
            errVal = SysLib.cread(indirectBlock, blockData);
            //failed to read
            if (errVal != SUCCESS) {
                entry.inode.finishWrite();
                return ERROR;
            }
            //updated traking variable
            blockIndex = indirectBlock;
        } else {
            //are we indirect
            if (blockNum + blockCount >= entry.inode.direct.length)//yes 
            {
                //do we have valid indirect pointer block
                if (entry.inode.indirect == -1) {
                    //if not get a new one
                    entry.inode.indirect = superBlock.getNextFreeBlock();
                }
                //error on loading/allocating indirect pointer block
                if (entry.inode.indirect == -1) {
                    entry.inode.finishWrite();
                    return ERROR;
                }
                //grab indirect pointer block
                indirectData = new byte[Disk.blockSize];
                errVal = SysLib.cread(entry.inode.indirect, indirectData);
                //failed to load indirect pointer block
                if (errVal != SUCCESS) {
                    entry.inode.finishWrite();
                    return ERROR;
                }
            }
            //if direct block is not there, get a new one
            if (entry.inode.direct[blockNum] == -1) {
                entry.inode.direct[blockNum] = superBlock.getNextFreeBlock();
            }
            //failed to load/allocate direct block
            if (entry.inode.direct[blockNum] == -1) {
                entry.inode.finishWrite();
                return ERROR;
            }
            //load direct block
            errVal = SysLib.cread(entry.inode.direct[blockNum], blockData);
            //failed to load block
            if (errVal != SUCCESS) {
                entry.inode.finishWrite();
                return ERROR;
            }
            //update block index
            blockIndex = entry.inode.direct[blockNum];
        }
        // Most simple version for small writes
        if (blockCount == 1) 
        {
            //load preexisting data
            System.arraycopy(buffer, 0,
                    blockData, blockOffset,
                    writeSize);
            //write the whole block data to the block
            errVal = SysLib.cwrite(blockIndex, blockData);
            //failed to write
            if (errVal != SUCCESS) {
                entry.inode.finishWrite();
                return ERROR;
            }
            //updated by how much we wrote
            entry.seekPtr += writeSize;
            entry.inode.length += writeSize;
        } 
        else //multiball madness
        {   
            //NOTE: the first time we write in multiblock we ALWAYS need to 
            //account for preexisting data, for all other writes, we don't
            //get where to start in current block
            int bufferOffset = Disk.blockSize - blockOffset;
            //copy data
            System.arraycopy(buffer, 0,
                    blockData, blockOffset,
                    bufferOffset);
            //write it all back
            errVal = SysLib.cwrite(blockIndex, blockData);
            //failed to write
            if (errVal != SUCCESS) {
                entry.inode.finishWrite();
                return ERROR;
            }
            //updated pointers by how much we read
            entry.seekPtr += bufferOffset;
            entry.inode.length += bufferOffset;
            //loop threw the number of blocks we have to write
            for (blockNum++; blockNum <= lastBlock; blockNum++) {
                
                // Bound the individual block read size by the size of the block itself
                int blockWriteSize = (writeSize - bufferOffset > Disk.blockSize)
                        ? Disk.blockSize : writeSize - bufferOffset;
                //NOTE:for simplisity check to see if we are a direct/indirect
                //block each loop, it can change from direct to indirect,
                //not vise-versa
                
                // Are we accessing an indirect block
                if (blockNum >= entry.inode.direct.length) 
                {
                    //get offset of the indirect block in the indirect pointer block
                    short indirectIndex = (short) ((blockNum - entry.inode.direct.length) * 2);
                    //read from the indirect block to get the block # to write to
                    blockIndex = SysLib.bytes2short(indirectData, indirectIndex);
                    //if we are 0, we need to allocate a new block
                    if (blockIndex == 0) {
                        //get new block
                        blockIndex = superBlock.getNextFreeBlock();
                        //failed to allocate new block
                        if (blockIndex == ERROR) {
                            entry.inode.finishWrite();
                            return ERROR;
                        }
                        //store new pointer to new indirect block into indirect
                        //pointer block
                        SysLib.short2bytes(blockIndex, indirectData, indirectIndex);
                        //save new indirect pointer block
                        errVal = SysLib.cwrite(entry.inode.indirect, indirectData);
                        //failed to save back
                        if (errVal != SUCCESS) {
                            entry.inode.finishWrite();
                            return ERROR;
                        }
                    }
                } else // Nope, we are a direct block
                {
                    //load direct block pointer
                    blockIndex = entry.inode.direct[blockNum];
                    //if we are -1 we need to allocate a new direct block
                    if (blockIndex == -1) {
                        //get new alloc
                        blockIndex = superBlock.getNextFreeBlock();
                        //error in alloc
                        if (blockIndex == -1) {
                            entry.inode.finishWrite();
                            return ERROR;
                        }
                        //save new direct pointer to the inode
                        entry.inode.direct[blockNum] = blockIndex;
                    }
                }
                //
                errVal = SysLib.cread(blockIndex, blockData);

                if (errVal != SUCCESS) {
                    entry.inode.finishWrite();
                    return ERROR;
                }

                System.arraycopy(buffer, bufferOffset,
                        blockData, 0,
                        blockWriteSize);
                //save back to disk
                errVal = SysLib.cwrite(blockIndex, blockData);
                //failed to write back
                if (errVal != SUCCESS) {
                    entry.inode.finishWrite();
                    return ERROR;
                }
                //increment by amount wrote
                entry.seekPtr += blockWriteSize;
                entry.inode.length += blockWriteSize;
                bufferOffset += blockWriteSize;
            }

        }
        //MR. Burns: RELEASE THE HOUNDS
        entry.inode.finishWrite();
        //return the total amount of data we wrote
        return writeSize;
    }
    /**
     * get the size of a file
     * @param fd file
     * @return return size/error
     */
    public int size(int fd) {
        //validity check
        if (fd < 3) {
            return ERROR;
        }
        //get the current proccess
        TCB tcb = scheduler.getMyTcb();
        //validity chec k
        if (tcb == null || fd >= tcb.ftEnt.length || tcb.ftEnt[fd] == null) {
            return ERROR;
        }
        //wait untill we can access the inode, write is going on
        tcb.ftEnt[fd].inode.waitUntilAccessable();
        //return the size after whatever write is finished
        return tcb.ftEnt[fd].inode.length;
    }

    public int format(int fileCount) {
        //NOTE: Check to see if anyone is open/writing/reading?
        //super block maintains the blocks, as such it is the one taht should
        //format them
        try {
            superBlock.formatDisk(fileCount);
            return SUCCESS;
        } catch (Exception e) {
            return ERROR;
        }
    }
    /**
     * Helper function for the seek function. Makes sure it is never set negative
     * or outside file size
     * @param seekPtr where we attempt to set the seek ptr
     * @param inode reference to the file so we can bind it to size
     * @return a value from 0 to file size
     */
    private int boundSeekPtr(int seekPtr, Inode inode) {
        return (seekPtr < 0) ? 0 : (seekPtr > inode.length) ? inode.length - 1 : seekPtr;
    }
    /**
     * 
     * @return disks directory data
     */
    private byte[] getDiskDirData() {
        //load the inode for the directory
        Inode rootInode = new Inode((short) DIR_INODE);
        //value to store error values for system calls
        int errVal = SUCCESS;
        
        byte[] blockData = new byte[Disk.blockSize];
        int blockCount = 0;
        for (; blockCount < rootInode.direct.length
                && rootInode.direct[blockCount] > DIR_INODE;
                blockCount++) ;

        byte[] indirect = null;
        int indirectCount = 0;
        if (rootInode.indirect > DIR_INODE) {
            indirect = new byte[Disk.blockSize];
            errVal = SysLib.cread(rootInode.indirect, indirect);

            if (errVal == 0) {
                for (int offset = 0; offset < Disk.blockSize; offset += 4) {
                    if (SysLib.bytes2int(indirect, offset) <= DIR_INODE) {
                        break;
                    }

                    blockCount++;
                    indirectCount++;
                }
            } else {
                return null;
            }
        }
        int blockIndex = 0;
        byte[] dirData = new byte[blockCount * Disk.blockSize];
        for (int i = 0; i < rootInode.direct.length && rootInode.direct[i] > DIR_INODE; i++) {
            errVal = SysLib.cread(rootInode.direct[i], blockData);

            if (errVal < SUCCESS) {
                return null;
            }

            System.arraycopy(blockData, 0,
                    dirData, blockIndex * Disk.blockSize,
                    Disk.blockSize);

            blockIndex++;
        }

        if (indirect != null) {
            for (int i = 0; i < indirectCount; i++) {
                errVal = SysLib.cread(indirect[SysLib.bytes2int(indirect, i * 4)], blockData);

                if (errVal < SUCCESS) {
                    return null;
                }

                System.arraycopy(blockData, 0,
                        dirData, blockIndex * Disk.blockSize,
                        Disk.blockSize);

                blockIndex++;
            }

            if (errVal < SUCCESS) {
                return null;
            }

            System.arraycopy(blockData, 0,
                    dirData, blockIndex * Disk.blockSize,
                    Disk.blockSize);

            blockIndex++;
        }

        return dirData;
    }
}

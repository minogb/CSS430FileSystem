
/**
 * This class contains and manages the file directory
 */
public class Directory 
{
    /**
     * max characters of each file name
     */
    private static int maxChars = 30;

    // Directory entries
    /**
     * contains the file name length of each entry
     */
    public int fsize[];
    /**
     * each file name entry
     */
    public char fnames[][];
    /**
     * creates a directory with a max num of files
     * @param maxInumber 
     */
    public Directory(int maxInumber) {
        //init 
        fsize = new int[maxInumber];
        fnames = new char[maxInumber][maxChars];
        //zero out data
        for (int i = 0; i < maxInumber; i++) {
            fsize[i] = 0;
            fnames[i][0] = 0;
        }
        //set the root of the directory
        String root = "/";
        // fsize[0] is the size of "/".
        fsize[0] = root.length();      
        // fnames[0] includes "/"
        root.getChars(0, fsize[0], fnames[0], 0); 
    }
    /**
     * convert the data to the directory
     * @param data containing directory info
     * @return size of directory/error
     */
    public int bytes2directory(byte[] data) {
        //validate
        if (data == null || data.length == 0) {
            return -1;
        }

        int offset = 0;
        int maxInumber = SysLib.bytes2int(data, offset);
        //validate
        if (maxInumber < 1) {
            return -1;
        }
        //offset starts at 4, size of int
        offset = 4;
        //init
        fsize = new int[maxInumber];
        fnames = new char[maxInumber][maxChars];

        int iNumber = 0;
        //load the data to the directory
        while (iNumber < maxInumber) {
            fsize[iNumber] = (char) SysLib.bytes2int(data, offset);
            offset += 4;

            for (int i = 0; i < fsize[iNumber]; i++) {
                fnames[iNumber][i] = (char) data[offset++];
            }

            iNumber++;
        }

        return maxInumber;
    }
    /**
     * @return the current directory as a byte[]
     */
    public byte[] directory2bytes() {
        int totalFSize = 0;
        for (int i = 0; i < fsize.length; i++) {
            totalFSize += fsize[i];
        }

        byte[] serialized = new byte[4 + fsize.length * 4 + totalFSize];

        int index = 0;

        SysLib.int2bytes(fsize.length, serialized, index);
        index = 4;

        for (int i = 0; i < fsize.length; i++) {
            SysLib.int2bytes(fsize[i], serialized, index);
            index += 4;

            for (int j = 0; j < fsize[i]; j++) {
                serialized[index++] = (byte) fnames[i][j];
            }

        }

        return serialized;
    }
    /**
     * allocate a new dir with an filename
     * @param filename the new file
     * @return success/ number
     */
    public short ialloc(String filename) {
        //validate
        if (filename.length() > maxChars) {
            return -1;
        }

        short iNumber = -1;
        short freeINumber = -1;

        // Find the first free space or determine if the file exists
        for (iNumber = 0; iNumber < fsize.length; iNumber++) {
            String existingFName = new String(fnames[iNumber]);

            if (fnames[iNumber][0] == 0 && freeINumber == -1) {
                freeINumber = iNumber;
            }

            if (compare(filename, fnames[iNumber]) == 0) {
                break;
            }
        }

        // If no free space was found or the file already exists
        if (freeINumber == -1 || iNumber < fsize.length) {
            return -1;
        }

        fsize[freeINumber] = filename.length();
        filename.getChars(0, fsize[freeINumber], fnames[freeINumber], 0);
        short val = (short) (freeINumber + 1);
        Inode inode = new Inode(val);
        inode.invalidate();
        inode.toDisk(val);

        return val;
    }
    /**
     * Free a dir based on inumber
     * @param iNumber number of inode
     * @return success/error
     */
    public boolean ifree(short iNumber) {
        //validate
        if (iNumber < 1 || iNumber > fsize.length || fnames[iNumber - 1] == null) {
            return false;
        }
        //load info of inode from memory
        Inode inode = new Inode(iNumber);
        //invalidate it
        inode.invalidate();
        //store the new invalidated inode to memory
        inode.toDisk(iNumber);
        //zero out its info in dir
        fsize[iNumber - 1] = 0;
        fnames[iNumber - 1][0] = 0;

        return true;
    }
    /**
     * get the inumber of a file
     * @param filename to lookup the inumber
     * @return the inumber
     */
    public short namei(String filename) {
        //validate
        if (filename.length() > maxChars) {
            return -1;
        }

        short iNumber;
        //lookup
        for (iNumber = 0; iNumber < fsize.length; iNumber++) {
            if (compare(filename, fnames[iNumber]) == 0) {
                break;
            }
        }
        //return number
        return (short) ((iNumber < fsize.length) ? iNumber + 1 : -1);
    }
    /**
     * get the filename based on the inumber
     * @param iNumber number of the inode
     * @return the name of the file
     */
    public String iname(short iNumber) {
        //validate
        if (iNumber < 1 || iNumber >= fsize.length) {
            return null;
        }
        //the filename
        return new String(fnames[iNumber]);
    }
    /**
     * we have had issues comparing strings/chars. this function does that
     * @param str string to compare with
     * @param cStr char string to compare to
     * @return false/true
     */
    public static byte compare(String str, char[] cStr) {
        char[] converted = new char[cStr.length];
        str.getChars(0, (str.length() > converted.length) ? converted.length : str.length(), converted, 0);

        for (int i = 0; i < converted.length; i++) {
            if (converted[i] < cStr[i]) {
                return -1;
            } else if (converted[i] > cStr[i]) {
                return 1;
            }
        }

        return 0;

    }
}

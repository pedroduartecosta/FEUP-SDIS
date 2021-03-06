package Peer;

import Channels.MC;
import Channels.MDB;
import Channels.MDR;
import Message.Message;
import Subprotocols.Backup;
import Subprotocols.Delete;
import Subprotocols.Restore;
import Subprotocols.SpaceReclaim;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.server.UnicastRemoteObject;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class Peer extends UnicastRemoteObject implements PeerInterface {

    private Restore restoreProtocol = null;
    private Delete deleteProtocol = null;
    private Backup backup = null;
    private SpaceReclaim spaceReclaimProtocol = null;
    private Map<String, Backup> backupProtocol = new ConcurrentHashMap<>();
    private String mc_ip, mdb_ip, mdr_ip;
    private int mc_port, mdb_port, mdr_port;
    private String peerId;
    private String peerAccessPoint;
    private String version;
    private int usedSpace = 0;
    private int diskSpace = 100 * 64000;
    private ExecutorService senderExecutor;
    private ExecutorService receiverExecutor;
    private ExecutorService deliverExecutor;

    /**
     * String is a par of fileId+chunkNo
     * String holds the desired replication degree
     */
    private Map<String, String> storedChunks = new ConcurrentHashMap<>();
    /**
     * Holds information about chunks replication degree in the network
     * String is a par of fileId+chunkNo
     * String holds the current replication degree
     */
    private Map<String, String> chunksReplicationDegree = new ConcurrentHashMap<>();

    /**
     * Holds information regarding if the chunk has been sent
     * String is a par of fileId+chunkNo
     * Boolean holds the current replication degree
     */
    private Map<String, Boolean> sentChunks = new ConcurrentHashMap<>();

    /**
     * Holds information about received DELETE Messages
     * String is the id of the file to be deleted
     * Message is the received DELETE message
     */
    private Map<String, Message> stackDeleteMessage = new ConcurrentHashMap<>();

    /**
     * Holds information about received Stored Messages
     * String is the unique combination of FileId+ChunkNo+SenderId
     */
    private Map<String, String> receivedStoredMessages = new ConcurrentHashMap<>();

    public Peer(String version, String peerId, String peerAccessPoint, String mc_ip, String mdb_ip, String mdr_ip, int mc_port, int mdb_port, int mdr_port) throws IOException {
        super();

        this.version = version;
        this.peerId = peerId;
        this.peerAccessPoint = peerAccessPoint;
        this.mc_ip = mc_ip;
        this.mc_port = mc_port;
        this.mdb_ip = mdb_ip;
        this.mdb_port = mdb_port;
        this.mdr_ip = mdr_ip;
        this.mdr_port = mdr_port;
        senderExecutor = Executors.newFixedThreadPool(5);
        deliverExecutor = Executors.newFixedThreadPool(11);
        receiverExecutor = Executors.newFixedThreadPool(10);

        MDB backupChannel = new MDB(mdb_ip, mdb_port, this);
        MDR restoreChannel = new MDR(mdr_ip, mdr_port, this);
        MC controlChannel = new MC(mc_ip, mc_port, this);


        //Creates peer "disk storage"
        File dir = new File(peerId);
        dir.mkdir();

        //loads information about chunks replication degree (if such exists)
        loadMetadataFromDisk();

        //Launches a thread for each channel to listen for requests
        backupChannel.listen();
        restoreChannel.listen();
        controlChannel.listen();


        restoreProtocol = new Restore(this);
        deleteProtocol = new Delete(this);
        spaceReclaimProtocol = new SpaceReclaim(this);
        backup = new Backup(this);

        //Sends pending PUTCHUNKS
        if (this.version.equals("1.1")) {
            backup.finishPendingTasks();
            deleteProtocol.sendAliveMessage();
        }

        saveMetadataToDisk();
    }

    /***
     * Starts backup protocol
     * @param file file to backup
     * @param replicationDegree  desired replication degree of the file
     */
    public void backup(String file, int replicationDegree) {


        this.backup = new Backup(file, replicationDegree, this);

        //Finishes sendind pending PUTCHUNKS (if any)
        backup.finishPendingTasks();

        //Reads chunks from a file and sends chunks to backup broadcast channel
        backup.readChunks();

        //Starts backup protocol
        backupProtocol.put(backup.getFileId(), backup);

        System.out.println("Finished Reading Chunks");
    }

    /**
     * Starts restore protocol
     *
     * @param file file to be restored
     */
    public void restore(String file) {

        restoreProtocol = new Restore(file, this);

        restoreProtocol.start();

        System.out.println("Restore completed");

    }

    /**
     * Starts space reclaim protocol which tries to free an given amount of space
     * if the amount of space is bigger than the disk space, it frees the whole
     * disk
     *
     * @param spaceToBeReclaimed amount of space to claim
     */
    public void spaceReclaim(int spaceToBeReclaimed) {
        spaceReclaimProtocol = new SpaceReclaim(this, spaceToBeReclaimed);
        spaceReclaimProtocol.start();
    }


    /***
     * Starts delete protocol
     * @param file
     */
    public void delete(String file) {

        //Starts delete protocol
        deleteProtocol = new Delete(file, this);


        deleteProtocol.start();


        System.out.println("Finished Reading Chunks");

    }

    /**
     * This operation allows to observe the service state. In response to such a request, the peer shall send to the client the following information:
     * For each file whose backup it has initiated:
     * The file pathname ✓
     * The backup service id of the file ✓
     * The desired replication degree ✓
     * For each chunk of the file:
     * Its id ✓
     * Its perceived replication degree ✓
     * For each chunk it stores:
     * Its id  ✓
     * Its size (in KBytes)
     * Its perceived replication degree ✓
     * The peer's storage capacity, i.e. the maximum amount of disk space that can be used to store chunks, and the amount of storage (both in KBytes) used to backup the chunks.
     */
    public void state() {

        String[] state = new String[1024];

        int i = 0;
        for (Backup b : backupProtocol.values()) {
            i++;
            state[i] = "File pathname: " + b.getPeer().peerId + "/" + b.getFileName();
            i++;
            state[i] = "Backup service id: " + b.getPeer().peerId;
            i++;
            state[i] = "Desired Replication degree: " + b.getReplicationDegree();

            for (int n = 1; n <= b.getNumberOfChunks(); n++) {
                i++;
                state[i] = "Chunk id: " + b.getFileId() + n;
                i++;
                state[i] = "Perceived replication degree: " + chunksReplicationDegree.get(b.getFileId() + n);
            }
        }
        for (Map.Entry<String, String> entry : storedChunks.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            i++;
            state[i] = "Stored chunk: " + key;
            i++;
            state[i] = "Perceived replication degree: " + value;

        }
        i++;
        state[i] = "Storage capacity = " + getStorageSpace() + " | Used space: " + getUsedSpace();

        for (String s : state) {
            if (s != null)
                System.out.println(s);
        }
    }

    /**
     * Adds a string with the par, fileId and chunkNo,
     * identifying a stored chunk and the desired replication degree
     *
     * @param fileId
     * @param chunkNo
     * @param desiredReplicationDegree
     */
    public void addChunkToRegistry(String fileId, String chunkNo, String desiredReplicationDegree) {

        this.storedChunks.put(fileId + chunkNo, desiredReplicationDegree);

    }

    /**
     * Increases registry about the number of times a chunk has been replicated
     *
     * @param message STORED message
     */
    public void increaseReplicationDegree(Message message) {

        String fileId = message.getMessageHeader().getFileId();
        String chunkNo = message.getMessageHeader().getChunkNo();
        String senderId = message.getMessageHeader().getSenderId();

        String chunkId = fileId + chunkNo;
        String currentReplicationDegree = chunksReplicationDegree.get(chunkId);

        if (receivedStoredMessages.get(chunkId + senderId) == null) {

            receivedStoredMessages.put(chunkId + senderId, senderId);

            if (currentReplicationDegree == null) {
                chunksReplicationDegree.put(chunkId, "1");
            } else {
                int temp = Integer.parseInt(currentReplicationDegree);
                chunksReplicationDegree.put(chunkId, String.valueOf(temp + 1));
            }

            if (version.equals("1.1")) {
                backup.finishTask(fileId + chunkNo);
            }
            saveMetadataToDisk();
        }


    }

    /**
     * Decreases registry about the number of times a chunk has been replicated
     *
     * @param fileId
     */
    public void decreaseReplicationDegree(String fileId, String chunkNo) {
        String chunkId = fileId + chunkNo;
        String currentReplicationDegree = chunksReplicationDegree.get(chunkId);

        if (currentReplicationDegree != null) {
            int temp = Integer.parseInt(currentReplicationDegree);
            chunksReplicationDegree.put(chunkId, String.valueOf(temp - 1));
        }
        saveMetadataToDisk();
    }


    /**
     * Saves information about chunks replication degree to non-volatile memory
     */
    public void saveMetadataToDisk() {
        Properties chunksRepDegProperties = new Properties();
        Properties storedChunksProperties = new Properties();
        Properties receivedStoredMessagesProperties = new Properties();

        chunksRepDegProperties.putAll(chunksReplicationDegree);
        storedChunksProperties.putAll(storedChunks);
        receivedStoredMessagesProperties.putAll(receivedStoredMessages);

        try {
            chunksRepDegProperties.store(new FileOutputStream(peerId + "/chunksRepDeg.properties"), null);
            storedChunksProperties.store(new FileOutputStream(peerId + "/storedChunks.properties"), null);
            receivedStoredMessagesProperties.store(new FileOutputStream(peerId + "/receivedStoredMessages.properties"), null);
        } catch (IOException e) {
            e.printStackTrace();
        }

        Properties diskInfo = new Properties();
        OutputStream output = null;

        try {
            output = new FileOutputStream(peerId + "/diskInfo.properties");
            diskInfo.setProperty("Used Space", Integer.toString(usedSpace));
            diskInfo.setProperty("Disk Size", Integer.toString(diskSpace));

            diskInfo.store(output, null);

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        }


    }

    /**
     * Loads information about chunks replication degree (if such exists)
     */
    private void loadMetadataFromDisk() {

        File chunksRepDegProperties = new File(peerId + "/chunksRepDeg.properties");
        File storedChunksProperties = new File(peerId + "/storedChunks.properties");
        File receivedStoredMessagesProperties = new File(peerId + "/receivedStoredMessages.properties");

        loadDataFromFile(chunksRepDegProperties, peerId + "/chunksRepDeg.properties", chunksReplicationDegree);
        loadDataFromFile(storedChunksProperties, peerId + "/storedChunks.properties", storedChunks);
        loadDataFromFile(receivedStoredMessagesProperties, peerId + "/receivedStoredMessages.properties", receivedStoredMessages);


        File diskInfo = new File(peerId + "/diskInfo.properties");

        if (diskInfo.exists() && !diskInfo.isDirectory()) {
            Properties properties = new Properties();
            try {
                properties.load(new FileInputStream(peerId + "/diskInfo.properties"));
            } catch (IOException e) {
                e.printStackTrace();
            }
            usedSpace = Integer.parseInt(properties.getProperty("Used Space"));
            diskSpace = Integer.parseInt(properties.getProperty("Disk Size"));
        }


    }

    /**
     * Loads metadata from disk
     *
     * @param file file containing the information
     * @param filePath file path
     * @param data map that stores the data
     */
    public void loadDataFromFile(File file, String filePath, Map data) {
        if (file.exists() && !file.isDirectory()) {
            Properties properties = new Properties();
            try {
                properties.load(new FileInputStream(filePath));
            } catch (IOException e) {
                e.printStackTrace();
            }

            for (String key : properties.stringPropertyNames()) {
                data.put(key, properties.get(key).toString());
            }
        }
    }

    /**
     * Check the replication degree of a certain chunk
     *
     * @param fileId  Id of the file that the chunk belongs to
     * @param chunkNo Chunk number
     * @return returns the replication degree of the chunk
     */
    public int getReplicationDegreeOfChunk(String fileId, String chunkNo) {

        if (chunksReplicationDegree.get(fileId + chunkNo) != null) {
            return Integer.parseInt(chunksReplicationDegree.get(fileId + chunkNo));
        } else {
            return 0;
        }
    }

    /**
     * Check the replication degree of a certain chunk
     *
     * @param key Id of the file + chunkNo
     * @return returns the replication degree of the chunk
     */
    public int getReplicationDegreeOfChunk(String key) {

        if (chunksReplicationDegree.get(key) != null) {
            return Integer.parseInt(chunksReplicationDegree.get(key));
        } else {
            return 0;
        }
    }

    /**
     * Returns the desired replication degree of a chunk from its key
     *
     * @param key   fileId+chunkNo
     * @return returns the desired replication degree of the chunk
     */
    public int getDesiredReplicationDegree(String key) {
        return Integer.parseInt(storedChunks.get(key));
    }


    /**
     * Verifies if the peer has this chunk
     *
     * @param fileId  Id of the file that the chunk belongs to
     * @param chunkNo Chunk number
     * @return returns true if the peer has the chunk and false otherwise
     */
    public boolean hasChunk(String fileId, String chunkNo) {

        return storedChunks.get(fileId + chunkNo) != null;

    }

    /**
     * Reads chunk from the disk
     *
     * @param fileId  Id of the file that the chunk belongs to
     * @param chunkNo Chunk number
     * @return returns a byte array with the chunk
     */
    public byte[] getChunk(String fileId, String chunkNo) {
        byte[] chunk = null;

        Path path = Paths.get(peerId + "/" + fileId + "/" + chunkNo);
        try {
            chunk = Files.readAllBytes(path);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return chunk;
    }

    /**
     * Adds boolean to sentChunks registry saying that the chunk has been sent
     *
     * @param fileId  Id of the file that the chunk belongs to
     * @param chunkNo Chunk number
     */
    public void addSentChunkInfo(String fileId, String chunkNo) {
        sentChunks.put(fileId + chunkNo, true);
    }


    /**
     * Returns true if it has a record that a peer has already sent the requested chunk
     *
     * @param fileId  Id of the file that the chunk belongs to
     * @param chunkNo Chunk number
     * @return Returns true if it has a record that a peer has already sent the requested chunk
     */
    public boolean hasChunkBeenSent(String fileId, String chunkNo) {
        return sentChunks.get(fileId + chunkNo) != null;

    }

    /**
     * Removes chunk from sent chunks database
     *
     * @param fileId    fileId of the chunk
     * @param chunkNo   chunk number
     */
    public void removeChunkFromSentChunks(String fileId, String chunkNo) {
        sentChunks.remove(fileId + chunkNo);
    }

    /**
     * Returns stored chunks hashmap
     *
     * @return Returns stored chunks hashmap
     */
    public Map<String, String> getStoredChunks() {
        return storedChunks;
    }

    /**
     * Getter for the space reclaim protocol
     *
     * @return returns the space reclaim protocol
     */
    public SpaceReclaim getSpaceReclaimProtocol() {
        return spaceReclaimProtocol;
    }


    /**
     * Adds message to the stack of received delete messages
     *
     * @param message DELETE message
     */
    public void addMessageToStackDelete(Message message) {
        String fileId = message.getMessageHeader().getFileId();
        this.stackDeleteMessage.put(fileId, message);

    }

    /**
     * Removes message from the stack
     *
     * @param fileId fileId of the deleted file
     */
    public void removeMessageFromStackDelete(String fileId) {
        this.stackDeleteMessage.remove(fileId);

    }

    /**
     * Removes chunk from the stored chunks hashmap
     *
     * @param chunkID fileId +  chunk number
     */
    public void removeChunkFromStoredChunks(String chunkID) {
        this.storedChunks.remove(chunkID);
    }

    /**
     * Removes chunk from chunks replication degree hashmap
     *
     * @param chunkId fileId + chunk number
     */
    public void removeFromChunksReplicationDegree(String chunkId) {
        this.chunksReplicationDegree.remove(chunkId);
    }

    public ExecutorService getReceiverExecutor() {
        return receiverExecutor;
    }

    public ExecutorService getSenderExecutor() {
        return senderExecutor;
    }

    public ExecutorService getDeliverExecutor() {
        return deliverExecutor;
    }

    public String getFileIdFromChunkId(String chunkId) {
        return chunkId.substring(0, 64);
    }

    public String getChunkNoFromChunkId(String chunkId) {
        return chunkId.substring(64, chunkId.length());
    }

    public int getStorageSpace() {
        return this.diskSpace;
    }

    public void setStorageSpace(int value) {
        this.diskSpace = value;
    }

    public Backup getBackup() {
        return this.backup;
    }

    public int getUsedSpace() {
        return usedSpace;
    }

    public void setUsedSpace(int usedSpace) {
        this.usedSpace = usedSpace;
    }

    public Restore getRestoreProtocol() {
        return restoreProtocol;
    }

    public String getMc_ip() {
        return mc_ip;
    }

    public String getMdb_ip() {
        return mdb_ip;
    }

    public String getMdr_ip() {
        return mdr_ip;
    }

    public int getMc_port() {
        return mc_port;
    }

    public int getMdb_port() {
        return mdb_port;
    }

    public int getMdr_port() {
        return mdr_port;
    }

    public String getPeerId() {
        return peerId;
    }

    public String getVersion() {
        return version;
    }

    public Delete getDeleteProtocol() {
        return deleteProtocol;
    }

    public Map<String, String> getChunksReplicationDegree() {
        return chunksReplicationDegree;
    }

    public Map<String, Message> getStackDeleteMessage() {
        return stackDeleteMessage;
    }
}


package memory.cache;

import memory.Memory;
import memory.cache.cacheReplacementStrategy.FIFOReplacement;
import memory.cache.cacheReplacementStrategy.ReplacementStrategy;
import util.Transformer;

import java.util.Arrays;

/**
 * 高速缓存抽象类
 */
public class Cache {

    public static  boolean isAvailable; // 默认启用Cache

    public static final int CACHE_SIZE_B = 1024 * 1024; // 1 MB 总大小

    public static final int LINE_SIZE_B = 1024; // 1 KB 行大小

    private final CacheLinePool cache = new CacheLinePool(CACHE_SIZE_B / LINE_SIZE_B);  // 总大小1MB / 行大小1KB = 1024个行

    private int SETS = 256;   // 组数

    private int setSize = 4;    // 每组行数

    // 单例模式
    private static final Cache cacheInstance = new Cache();

    private Cache() {
    }

    public static Cache getCache() {
        return cacheInstance;
    }

    private ReplacementStrategy replacementStrategy = new FIFOReplacement();;    // 替换策略

    public static boolean isWriteBack;   // 写策略

    private final Transformer transformer = new Transformer();

    Memory memory = Memory.getMemory();



    /**
     * 读取[pAddr, pAddr + len)范围内的连续数据，可能包含多个数据块的内容
     *
     * @param pAddr 数据起始点(32位物理地址 = 22位块号 + 10位块内地址)
     * @param len   待读数据的字节数
     * @return 读取出的数据，以char数组的形式返回
     */
    public char[] read(String pAddr, int len) {
        char[] data = new char[len];
        int addr = Integer.parseInt(transformer.binaryToInt("0" + pAddr));
        int upperBound = addr + len;
        int index = 0;
        while (addr < upperBound) {
            int nextSegLen = LINE_SIZE_B - (addr % LINE_SIZE_B);
            if (addr + nextSegLen >= upperBound) {
                nextSegLen = upperBound - addr;
            }
            int rowNO = fetch(transformer.intToBinary(String.valueOf(addr)));
            char[] cache_data = cache.get(rowNO).getData();
            int i = 0;
            while (i < nextSegLen) {
                data[index] = cache_data[addr % LINE_SIZE_B + i];
                index++;
                i++;
            }
            addr += nextSegLen;
        }
        return data;
    }

    /**
     * 向cache中写入[pAddr, pAddr + len)范围内的连续数据，可能包含多个数据块的内容
     *
     * @param pAddr 数据起始点(32位物理地址 = 22位块号 + 10位块内地址)
     * @param len   待写数据的字节数
     * @param data  待写数据
     */
    public void write(String pAddr, int len, char[] data) {
        int addr = Integer.parseInt(transformer.binaryToInt("0" + pAddr));
        int upperBound = addr + len;
        int index = 0;
        while (addr < upperBound) {
            int nextSegLen = LINE_SIZE_B - (addr % LINE_SIZE_B);
            if (addr + nextSegLen >= upperBound) {
                nextSegLen = upperBound - addr;
            }
            int rowNO = fetch(transformer.intToBinary(String.valueOf(addr)));
            char[] cache_data = cache.get(rowNO).getData();
            int i = 0;
            while (i < nextSegLen) {
                cache_data[addr % LINE_SIZE_B + i] = data[index];
                index++;
                i++;
            }

            // TODO
            cache.clPool[rowNO].data = cache_data;
            cache.clPool[rowNO].dirty = true;

            if(!isWriteBack){
                memory.write(calculatePAddr(rowNO) + "0000000000",LINE_SIZE_B,cache_data);
                cache.clPool[rowNO].validBit = true;
                cache.clPool[rowNO].dirty = false;
            }

            addr += nextSegLen;
        }
    }

    // 通过rowNO计算出cache对应主存中的位置，方便写回。
    public String calculatePAddr(int rowNO){
        char [] c1 = cache.clPool[rowNO].tag;
        int groupNO = rowNO / setSize;
        String s3 = Integer.toBinaryString(groupNO);
        while(s3.length() + calculateTag(SETS) < 22){
            s3 = "0" + s3;
        }
        return String.valueOf(c1).substring(0,calculateTag(SETS)) + s3;
    }

    /**
     * 查询{@link Cache#cache}表以确认包含pAddr的数据块是否在cache内
     * 如果目标数据块不在Cache内，则将其从内存加载到Cache
     *
     * @param pAddr 数据起始点(32位物理地址 = 22位块号 + 10位块内地址)
     * @return 数据块在Cache中的对应行号
     */
    private int fetch(String pAddr) {
        // TODO
        int blockNO = getBlockNO(pAddr);
        int rowNO = map(blockNO);
        if(rowNO != -1 && cache.clPool[rowNO].validBit){
            // 命中

            // 直接映射无替换策略
            if(setSize != 1 ){
                replacementStrategy.hit(rowNO);
            }
            return rowNO;
        }else{
            int groupNO = blockNO % SETS;
            char[] newBlock = memory.read(pAddr.substring(0,22) + "0000000000",1024);
            // 直接映射，无替换策略
            if(setSize == 1){
                update(groupNO,(pAddr.substring(0,12)+"0000000000").toCharArray(),newBlock);
                return groupNO;
            }
            // 有替换策略
            else{
                String s2 = pAddr.substring(0,calculateTag(SETS));
                while(s2.length() < 22) s2 = s2 + "0";
                int result = replacementStrategy.replace(groupNO*setSize,groupNO*setSize+setSize-1,s2.toCharArray(),newBlock);
                replacementStrategy.hit(result);
                return result;
            }
        }
    }

    /**
     * 根据目标数据内存地址前22位的int表示，进行映射
     *
     * @param blockNO 数据在内存中的块号
     * @return 返回cache中所对应的行，-1表示未命中
     */
    private int map(int blockNO) {
        // TODO
        int groupNO = blockNO % SETS;
        int rowNO;
        int tagLength = calculateTag(SETS);
        String theTag = Integer.toBinaryString(blockNO);
        while(theTag.length() < 22) theTag = "0" + theTag;
        theTag = theTag.substring(0,tagLength);
        String cacheTag;
        for(rowNO = groupNO * setSize; rowNO < (groupNO+1)*setSize; rowNO++){
            cacheTag = String.valueOf(cache.get(rowNO).getTag()).substring(0,tagLength);
            if(theTag.equals(cacheTag)) return rowNO;
        }
        return -1;
    }

    /**
     * 更新cache
     *
     * @param rowNO 需要更新的cache行号
     * @param tag   待更新数据的Tag
     * @param input 待更新的数据
     */
    public void update(int rowNO, char[] tag, char[] input) {
        // TODO
        // 把更新过的数据写回内存
        if(isWriteBack && cache.clPool[rowNO].dirty && cache.clPool[rowNO].validBit){
            memory.write(calculatePAddr(rowNO) + "0000000000",LINE_SIZE_B,cache.clPool[rowNO].data);
        }
        cache.clPool[rowNO].dirty = false;
        cache.clPool[rowNO].tag = tag;
        cache.clPool[rowNO].data = input;
        cache.clPool[rowNO].validBit = true;
        cache.clPool[rowNO].visited = 0;
    }

    public int calculateTag(int SETS){
        String s2 = Integer.toBinaryString(SETS);
        int t1 = s2.length();
        return 22 + 1 - t1;
    }

    /**
     * 从32位物理地址(22位块号 + 10位块内地址)获取目标数据在内存中对应的块号
     *
     * @param pAddr 32位物理地址
     * @return 数据在内存中的块号
     */
    private int getBlockNO(String pAddr) {
        return Integer.parseInt(transformer.binaryToInt("0" + pAddr.substring(0, 22)));
    }


    /**
     * 该方法会被用于测试，请勿修改
     * 使用策略模式，设置cache的替换策略
     *
     * @param replacementStrategy 替换策略
     */
    public void setReplacementStrategy(ReplacementStrategy replacementStrategy) {
        this.replacementStrategy = replacementStrategy;
    }

    /**
     * 该方法会被用于测试，请勿修改
     *
     * @param SETS 组数
     */
    public void setSETS(int SETS) {
        this.SETS = SETS;
    }

    /**
     * 该方法会被用于测试，请勿修改
     *
     * @param setSize 每组行数
     */
    public void setSetSize(int setSize) {
        this.setSize = setSize;
    }

    /**
     * 告知Cache某个连续地址范围内的数据发生了修改，缓存失效
     * 该方法仅在memory类中使用，请勿修改
     *
     * @param pAddr 发生变化的数据段的起始地址
     * @param len   数据段长度
     */
    public void invalid(String pAddr, int len) {
        int from = getBlockNO(pAddr);
        Transformer t = new Transformer();
        int to = getBlockNO(t.intToBinary(String.valueOf(Integer.parseInt(t.binaryToInt("0" + pAddr)) + len - 1)));

        for (int blockNO = from; blockNO <= to; blockNO++) {
            int rowNO = map(blockNO);
            if (rowNO != -1) {
                cache.get(rowNO).validBit = false;
            }
        }
    }

    /**
     * 清除Cache全部缓存
     * 该方法会被用于测试，请勿修改
     */
    public void clear() {
        for (CacheLine line : cache.clPool) {
            if (line != null) {
                line.validBit = false;
            }
        }
    }

    /**
     * 输入行号和对应的预期值，判断Cache当前状态是否符合预期
     * 这个方法仅用于测试，请勿修改
     *
     * @param lineNOs     行号
     * @param validations 有效值
     * @param tags        tag
     * @return 判断结果
     */
    public boolean checkStatus(int[] lineNOs, boolean[] validations, char[][] tags) {
        if (lineNOs.length != validations.length || validations.length != tags.length) {
            return false;
        }
        for (int i = 0; i < lineNOs.length; i++) {
            CacheLine line = cache.get(lineNOs[i]);
            if (line.validBit != validations[i]) {
                return false;
            }
            if (!Arrays.equals(line.getTag(), tags[i])) {
                return false;
            }
        }
        return true;
    }


    /**
     * 负责对CacheLine进行动态初始化
     */
    private static class CacheLinePool {

        private final CacheLine[] clPool;

        /**
         * @param lines Cache的总行数
         */
        CacheLinePool(int lines) {
            clPool = new CacheLine[lines];
        }

        private CacheLine get(int rowNO) {
            CacheLine l = clPool[rowNO];
            if (l == null) {
                clPool[rowNO] = new CacheLine();
                l = clPool[rowNO];
            }
            return l;
        }
    }


    /**
     * Cache行，每行长度为(1+22+{@link Cache#LINE_SIZE_B})
     */
    private static class CacheLine {

        // 有效位，标记该条数据是否有效
        boolean validBit = false;

        // 脏位，标记该条数据是否被修改
        boolean dirty = false;

        // 用于LFU算法，记录该条cache使用次数
        int visited = 0;

        // 用于LRU和FIFO算法，记录该条数据时间戳
        Long timeStamp = 0L;

        // 标记，占位长度为22位，有效长度取决于映射策略：
        // 直接映射: 12 位
        // 全关联映射: 22 位
        // (2^n)-路组关联映射: 22-(10-n) 位
        // 注意，tag在物理地址中用高位表示，如：直接映射(32位)=tag(12位)+行号(10位)+块内地址(10位)，
        // 那么对于值为0b1111的tag应该表示为0000000011110000000000，其中前12位为有效长度
        char[] tag = new char[22];

        // 数据
        char[] data = new char[LINE_SIZE_B];

        char[] getData() {
            return this.data;
        }

        char[] getTag() {
            return this.tag;
        }

    }


    public boolean isValid(int rowNO) {
        return cache.get(rowNO).validBit;
    }

    // 获取脏位
    public boolean isDirty(int rowNO) {
        return cache.get(rowNO).dirty;
    }

    // 获取该行数据
    public char[] getData(int rowNO) {
        return cache.get(rowNO).getData();
    }

    // 重置时间戳
    public void setTimeStamp(int rowNO,Long timeStamp) {
        cache.clPool[rowNO].timeStamp = timeStamp;
    }

    // 获取时间戳
    public Long getTimeStamp(int rowNO){
        return cache.clPool[rowNO].timeStamp;
    }

    // 增加访问次数
    public void addVisited(int rowNO){
        cache.clPool[rowNO].visited += 1;
    }

    // 获取访问次数
    public int getVisited(int rowNO){
        return cache.clPool[rowNO].visited;
    }

    // 获取该行数据
//    public char[] getData(int rowNO){
//
//    }

    public  boolean getValid(int rowNO){ return cache.clPool[rowNO].validBit;}
}

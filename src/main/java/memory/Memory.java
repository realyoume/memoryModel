package memory;

import memory.cache.Cache;
import memory.disk.Disk;
import util.Transformer;

/**
 * 内存抽象类
 */
public class Memory {

    /*
     * ------------------------------------------
     *            |  Segment=true | Segment=false
     * ------------------------------------------
     * Page=true  |     段页式    |    不存在
     * ------------------------------------------
     * Page=false |    只有分段   |   实地址模式
     * ------------------------------------------
     */

    public static boolean SEGMENT = false;

    public static boolean PAGE = false;

    public static int MEM_SIZE_B = 32 * 1024 * 1024;      // 主存大小 32 MB

    public static int PAGE_SIZE_B = 4 * 1024;      // 页大小 4 KB

    private final char[] memory = new char[MEM_SIZE_B];

    private SegDescriptor[] GDT = new SegDescriptor[8 * 1024];  // 全局描述符表

    private final PageItem[] pageTbl = new PageItem[Disk.DISK_SIZE_B / Memory.PAGE_SIZE_B]; // 页表

    private boolean[] pageValid = new boolean[MEM_SIZE_B / PAGE_SIZE_B];

    private static final Memory memoryInstance = new Memory(); // 单例模式

    private Memory() {
    }

    public static Memory getMemory() {
        return memoryInstance;
    }

    private final Disk disk = Disk.getDisk();

    private final Transformer transformer = new Transformer();

    public static boolean timer = false;

    /**
     * 根据物理地址读取数据
     * 注意， read方法应该在load方法被调用之后调用，即read方法的目标页(如果开启分页)都是合法的
     *
     * @param pAddr 32位物理地址
     * @param len   待读取数据的长度
     * @return 读取出来的数据
     */
    public char[] read(String pAddr, int len) {
        if (timer) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        char[] data = new char[len];
        int base = Integer.parseInt(transformer.binaryToInt(pAddr));
        System.arraycopy(memory, base, data, 0, len);
        return data;
    }

    /**
     * 根据物理地址写数据
     *
     * @param pAddr 32位物理地址
     * @param len   待读取数据的长度
     */
    public void write(String pAddr, int len, char[] data) {
        if (timer) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        // 通知Cache缓存失效
        Cache.getCache().invalid(pAddr, len);
        // 更新数据
        int start = Integer.parseInt(transformer.binaryToInt(pAddr));
        System.arraycopy(data, 0, memory, start, len);
    }

    /**
     * 实模式下从磁盘中加载数据
     *
     * @param pAddr 实模式下，内存地址对应磁盘地址
     * @param len   数据段长度
     */
    public void real_load(String pAddr, int len) {
        // TODO: paste your code here
        char[] data = disk.read(pAddr,len);
        write(pAddr,len,data);
    }

    /**
     * 段式存储模式下，从磁盘中加载数据.段页式存储中，不用从磁盘中加载数据
     *
     * @param segIndex 段索引
     */
    public void seg_load(int segIndex) {
        // TODO: paste your code here
        if(!PAGE){
            // 分段式，把整个段加载进来
            char[] base = getBaseOfSegDes(segIndex);

            int len = (int) Math.pow(2,20);
            char[] data = disk.read(String.valueOf(base), len);

            write("00000000000000000000000000000000",len,data);
        }


        // 分段式，初始化段表项
        // 段页式，初始化段表项即可，之后会分页加载进内存
        SegDescriptor theSegDescriptor = getSegDescriptor(segIndex);
        theSegDescriptor.base = "00000000000000000000000000000000".toCharArray();
        theSegDescriptor.limit = "11111111111111111111".toCharArray();
        theSegDescriptor.validBit = true;
        theSegDescriptor.granularity = PAGE;

        //
        for (int i = 0; i < MEM_SIZE_B / PAGE_SIZE_B;i++){
            pageValid[i] = true;
        }
    }


    /**
     * 段页式存储下，从磁盘中加载数据
     * 不考虑32MB内存用满的情况
     *
     * @param vPageNo 虚拟页号
     */
    public void page_load(int vPageNo) {
        // TODO: paste your code here
        String linearAddr =  Integer.toBinaryString(vPageNo);
        while(linearAddr.length() < 20) linearAddr =  "0" +linearAddr ;
        while(linearAddr.length() < 32) linearAddr =  linearAddr + "0" ;
        int len = (int)Math.pow(2,12);
        char[] data = disk.read(linearAddr,len);

        // 找到内存中空闲的页面并写入
        for (int i = 0; i < Disk.DISK_SIZE_B / Memory.PAGE_SIZE_B;i++){
            if(pageValid[i]){
                String physicalAddr =  Integer.toBinaryString(i);
                while(physicalAddr.length()<20) physicalAddr = "0"+ physicalAddr;
                write(physicalAddr +"000000000000",len,data);
                getPageItem(vPageNo).pageFrame = physicalAddr.toCharArray();
                getPageItem(vPageNo).isInMem = true;
                pageValid[i] = false;
                break;
            }
        }
    }

    public void clear() {
        GDT = new SegDescriptor[8 * 1024];
        for (PageItem pItem : pageTbl) {
            if (pItem != null) {
                pItem.isInMem = false;
            }
        }
        pageValid = new boolean[MEM_SIZE_B / PAGE_SIZE_B];
    }


    /**
     * 理论上应该为Memory分配出一定的系统保留空间用于存放段表和页表，并计算出每个段表和页表所占的字节长度，通过地址访问
     * 不过考虑到Java特性，在此作业的实现中为了简化难度，全部的32M内存都用于存放数据，段表和页表直接用独立的数据结构表示，不占用"内存空间"
     * 除此之外，理论上每个进程都会有对应的段表和页表，作业中则假设系统只有一个进程，因此段表和页表也只有一个，不需要再根据进程号选择相应的表
     */
    private static class SegDescriptor {

        private char[] base = new char[32];  // 32位基地址

        private char[] limit = new char[20]; // 20位限长，表示段在内存中的长度

        private boolean validBit = false;    // 有效位,为true表示被占用（段已在内存中），为false表示空闲（不在内存中）

        private boolean granularity = false;    // 粒度，为true表示段以页（4KB）为基本单位，为false表示段以字节为基本单位
    }

    private SegDescriptor getSegDescriptor(int index) {
        if (GDT[index] == null) {
            GDT[index] = new SegDescriptor();
        }
        return GDT[index];
    }

    /**
     * 根据segment descriptor的索引返回该SegDescriptor的limit
     *
     * @param index 段索引
     * @return 20-bits
     */
    public char[] getLimitOfSegDes(int index) {
        return getSegDescriptor(index).limit;
    }


    /**
     * 根据segment descriptor的索引返回该SegDescriptor的base
     *
     * @param index 段索引
     * @return 32-bits
     */
    public char[] getBaseOfSegDes(int index) {
        return getSegDescriptor(index).base;
    }

    /**
     * 根据segment descriptor的索引返回该SegDescriptor是否有效
     *
     * @param index 段索引
     * @return boolean
     */
    public boolean isValidSegDes(int index) {
        return getSegDescriptor(index).validBit;
    }

    /**
     * 根据segment descriptor的索引返回该SegDescriptor的粒度
     *
     * @param index 段索引
     * @return boolean
     */
    public boolean isGranularitySegDes(int index) {
        return getSegDescriptor(index).granularity;
    }

    /**
     * 强制创建一个段描述符，指向指定的物理地址
     * 此方法仅被测试用例使用
     *
     * @param segIndex 段索引
     * @param base     段基址
     * @param len      段长
     */
    public void alloc_seg_force(int segIndex, String base, int len, boolean granularity) {
        SegDescriptor sd = getSegDescriptor(segIndex);
        sd.base = base.toCharArray();
        sd.limit = transformer.intToBinary(String.valueOf(len)).substring(1, 32).toCharArray();
        sd.validBit = true;
        sd.granularity = granularity;
    }


    /**
     * 页表项为长度为20-bits的页框号
     */
    private static class PageItem {

        private char[] pageFrame;   // 物理页框号

        private boolean isInMem = false;    // 装入位
    }

    private PageItem getPageItem(int index) {
        if (pageTbl[index] == null) {
            pageTbl[index] = new PageItem();
        }
        return pageTbl[index];
    }

    /**
     * 根据page索引返回该page是否在内存中
     *
     * @param vPageNo 虚拟页号
     * @return boolean
     */
    public boolean isValidPage(int vPageNo) {
        if (timer) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return getPageItem(vPageNo).isInMem;
    }


    /**
     * 根据虚页页号返回该页的物理页框号
     *
     * @param vPageNo 虚拟页号
     * @return 20-bits
     */
    public char[] getFrameOfPage(int vPageNo) {
        if (timer) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return getPageItem(vPageNo).pageFrame;
    }

}
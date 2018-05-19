package cn.mageek.datanode.main;

import cn.mageek.common.model.HeartbeatType;
import cn.mageek.datanode.job.DataRunnable;
import cn.mageek.datanode.job.Heartbeat;
import cn.mageek.datanode.res.CommandFactory;
import cn.mageek.datanode.res.JobFactory;
import cn.mageek.datanode.service.DataManager;
import cn.mageek.datanode.service.CronJobManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import static cn.mageek.common.util.PropertyLoader.load;

/**
 * 管理本应用的所有服务
 * redis-cli -h 127.0.0.1 -p 10100
 * @author Mageek Chiu
 * @date 2018/3/5 0005:19:26
 */

public class DataNode {

    private static final Logger logger = LoggerFactory.getLogger(DataNode.class);
    private static final String pid = ManagementFactory.getRuntimeMXBean().getName();
    private static int offlinePort = 6666;// 默认值，可调
    private static String offlineCmd = "k";

    //本节点的数据存储，ConcurrentHashMap 访问效率高于 ConcurrentSkipListMap，但是转移数据时就需要遍历而不能直接排序了，考虑到转移数据情况并不多，访问次数远大于转移次数，所以就不用ConcurrentSkipListMap
//    private static volatile Map<String,String> DATA_POOL = new ConcurrentHashMap<>(1024) ;// 被置为null 则意味着节点该下线了
    public static volatile Map<String,String> DATA_POOL = new ConcurrentHashMap<>(1024) ;// 其他对象都有自己的引用实例域DATA_POOL，把他们的域置为null并不会使这个域DATA_POOL为null，所以要改变对象本身
    public static volatile Map<String,Long> DATA_EXPIRE = new ConcurrentHashMap<>(1024);// 键-失效时间的秒表示
    public static volatile CountDownLatch countDownLatch;//任务个数



    public static void main(String[] args){
        Thread currentThread = Thread.currentThread();
        currentThread.setName(("DataNode"+Math.random()*100).substring(0,10));
        String threadName = currentThread.getName();
        logger.debug("current thread {}" ,threadName);

        Thread dataManager,cronJobManager;
        int jobNumber = 2;countDownLatch = new CountDownLatch(jobNumber);

        try(InputStream in = ClassLoader.class.getResourceAsStream("/app.properties")){
            Properties pop = new Properties(); pop.load(in);
            offlinePort = Integer.parseInt(load(pop,"datanode.offline.port")); //下线监听端口
            offlineCmd = load(pop,"datanode.offline.cmd"); //下线命令字
            logger.debug("config offlinePort:{},offlineCmd:{}", offlinePort,offlineCmd);

            // 初始化命令对象
//            DATA_POOL.put("clientPort",clientPort);// 有了这一句下面才是DATA_POOL:1132277150,1。否则就是 DATA_POOL:0,0
//            logger.debug("DATA_POOL:{},{}",DATA_POOL.hashCode(),DATA_POOL.size());
//            CommandFactory.construct(DATA_POOL);// 所有command都是单例对象，共享这一个数据池（ConcurrentHashMap）
            CommandFactory.construct();// 所有command都是单例对象

            // 初始化任务对象
//            JobFactory.construct(DATA_POOL);// 任务也可能用到数据池
            JobFactory.construct();

            //放入一些数据 做测试
//            DATA_POOL.put(offlineKey,onlineValue);// 在线标志
            for (int i = 1 ; i <= 25 ; i++ ) DATA_POOL.put(threadName+i,threadName);


            // n 个线程分别启动 n 个服务
            dataManager = new Thread(new DataManager(),"DataManager");dataManager.start();
            cronJobManager = new Thread(new CronJobManager(),"CronJobManager");cronJobManager.start();

            //等待其他几个线程完全启动，然后才能对外提供服务
            countDownLatch.await();
            logger.info("DataNode is fully up now, pid:{}",pid);

            // 监听来自外部的下线消息，收到后立马给NameNode发消息，经过允许和转移数据完成后才能下线也就是把DATA_POOL置为null

            // 环境变量的方式
//            String offline = System.getenv("DataNodeOffline");
//            // 使用getProperties获得的其实是虚拟机的变量形如： -Djavaxxxx。
//            // getenv方法才是真正的获得系统环境变量，比如Path之类。
//            while (offline==null){
//                offline = System.getenv("DataNodeOffline");// windows: set DataNodeOffline true 但是不是全局，只对当前cmd界面管用，所以不可行
//                Thread.sleep(5000);// 睡5秒再检查
//            }
//            dataTransfer();

            // 信号的方式
//            Signal.handle(new Signal("ILL"),new MySignalHandler());// 注册信号，但是windows的信号比较麻烦

            // 开启socket，这样就能用telnet的方式来发送下线命令了
            signalHandler();

        }catch(Exception ex) {
            logger.error("DataNode start error:",ex);
            CommandFactory.destruct();
            JobFactory.destruct();
        }
    }

//    static class MySignalHandler implements SignalHandler {
//        @Override
//        public void handle(Signal signal) {// 收到信号，给nameNode发送请求
//            signal.getName();
//            dataTransfer();
//        }
//    }

    private static void signalHandler() {
        ServerSocket serverSocket;
        try {
            serverSocket = new ServerSocket(offlinePort);// 监听信号端口，telnet 127.0.0.1 6666 。 输入字母 k 按回车就行
            while (true) {
                //接收客户端连接的socket对象
                try (Socket connection = serverSocket.accept()) {// 使用这个方式，收到消息后连接马上就断开了
                    //接收客户端传过来的数据，会阻塞
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    String msg = br.readLine();
                    logger.info("signalHandler received msg: {}",msg );
                    Writer writer = new OutputStreamWriter(connection.getOutputStream());
                    if (offlineCmd.equals(msg)){
                        writer.append("going down");writer.flush();writer.close();
                        dataTransfer();
                        break;// 可以下线了，所以也不必监听这个端口了
                    }else{
                        writer.append("bad option");writer.flush();writer.close();
                    }
                } catch (Exception e) {
                    logger.error("signalHandler connection error", e);
                }
            }
        } catch (IOException e) {
            logger.error("signalHandler ServerSocket error",e);
        }
    }

    /**
     * 收到下线信号，先转移数据，阻塞至数据转移完成
     */
    private static void dataTransfer() throws InterruptedException {
        logger.warn("get offline signal");
        Heartbeat heartbeat = (Heartbeat) JobFactory.getJob("Heartbeat");
        heartbeat.run1(HeartbeatType.OFFLINE);// heartbeat对象的连接早已打开并且由定时任务一直保持着，所以主线程直接发起下线请示与数据转移工作

        while (DATA_POOL != null){// 依然在线
//        while (DATA_POOL.getOrDefault(offlineKey,onlineValue).equals(onlineValue)){// 依然在线
            Thread.sleep(5000);// 睡5秒再检查
            logger.debug("waiting for dataTransfer to complete");
        }
        // 数据已转移完毕并清空，可以下线
        logger.info("DataNode can be safely shutdown now,{}",pid);// DATA_POOL == null，数据转移完成，可以让运维手动关闭本进程了
    }

}

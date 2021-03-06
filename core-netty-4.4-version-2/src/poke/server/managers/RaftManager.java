package poke.server.managers;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.beans.Beans;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import poke.comm.App.JoinMessage;
import poke.comm.App.Request;
import poke.core.Mgmt.Management;
import poke.core.Mgmt.MgmtHeader;
import poke.core.Mgmt.RaftMessage;
import poke.core.Mgmt.RaftMessage.RaftAction;
import poke.server.ServerInitializer;
import poke.server.conf.ClusterConfList;
import poke.server.conf.ClusterConfList.ClusterConf;
import poke.server.conf.NodeDesc;
import poke.server.conf.ServerConf;
import poke.server.election.Election;
import poke.server.election.ElectionListener;
import poke.server.election.LogMessage;
import poke.server.election.RaftElection;
import poke.server.managers.ConnectionManager.connectionState;

public class RaftManager implements ElectionListener {

	protected static Logger logger = LoggerFactory.getLogger("election");
	protected static AtomicReference<RaftManager> instance = new AtomicReference<RaftManager>();

	private static ServerConf conf;
	private static ClusterConfList clusterConf;
	private static long lastKnownBeat = System.currentTimeMillis();
	// number of times we try to get the leader when a node starts up
	private int firstTime = 2;
	/** The election that is in progress - only ONE! */
	private Election election;
	private int electionCycle = -1;
	private Integer syncPt = 1;
	/** The leader */
	Integer leaderNode;

	public static RaftManager initManager(ServerConf conf, ClusterConfList clusterConfList) {
		RaftManager.conf = conf;
		RaftManager.clusterConf = clusterConfList;
		instance.compareAndSet(null, new RaftManager());
		
		return instance.get();
	}
	
	public void processRequest(Management mgmt) {
		if (!mgmt.hasRaftmessage())
			return;
		RaftMessage rm = mgmt.getRaftmessage();
		// when a new node joins the network it will want to know who the leader
		// is - we kind of ram this request-response in the process request
		// though there has to be a better place for it
		if (rm.getRaftAction().getNumber() == RaftAction.WHOISTHELEADER_VALUE) {
			respondToWhoIsTheLeader(mgmt);
			return;
		}
		// else respond to the message using process Function in RaftElection

		Management rtn = electionInstance().process(mgmt);
		if (rtn != null)
			ConnectionManager.broadcastAndFlush(rtn);
	}

	/**
	 * check the health of the leader (usually called after a HB update)
	 * 
	 * @param mgmt
	 */
	public boolean assessCurrentState() {
		// give it two tries to get the leader else return true to start election 
		if (firstTime > 0) {
			this.firstTime--;
			logger.info("In assessCurrentState() if condition");
			askWhoIsTheLeader();
			return false;
		}
		else 
			return true;//starts Elections
	}

	private void askWhoIsTheLeader() {
		logger.info("Node " + conf.getNodeId() + " is searching for the leader");
		if (whoIsTheLeader() == null) {
			logger.info("----> I cannot find the leader is! I don't know!");
			return;
		} else {
			logger.info("The Leader is " + this.leaderNode);
		}

	}

	private void respondToWhoIsTheLeader(Management mgmt) {
		if (this.leaderNode == null) {
			logger.info("----> I cannot respond to who the leader is! I don't know!");
			return;
		}
		logger.info("Node " + conf.getNodeId() + " is replying to "
				+ mgmt.getHeader().getOriginator()
				+ "'s request who the leader is. Its Node " + this.leaderNode);

		MgmtHeader.Builder mhb = MgmtHeader.newBuilder();
		mhb.setOriginator(conf.getNodeId());
		mhb.setTime(System.currentTimeMillis());
		mhb.setSecurityCode(-999); 

		RaftMessage.Builder rmb = RaftMessage.newBuilder();
		rmb.setLeader(this.leaderNode);
		rmb.setRaftAction(RaftAction.THELEADERIS);

		Management.Builder mb = Management.newBuilder();
		mb.setHeader(mhb.build());
		mb.setRaftmessage(rmb);
		try {

			Channel ch = ConnectionManager.getConnection(mgmt.getHeader()
					.getOriginator(), connectionState.SERVERMGMT);
			if (ch != null)
				ch.writeAndFlush(mb.build());

		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	private Election electionInstance() {
		if (election == null) {
			synchronized (syncPt) {
				if (election != null)
					return election;

				// new election
				String clazz = RaftManager.conf.getElectionImplementation();

				// if an election instance already existed, this would
				// override the current election
				try {
					election = (Election) Beans.instantiate(this.getClass()
							.getClassLoader(), clazz);
					election.setNodeId(conf.getNodeId());
					election.setListener(this);
				} catch (Exception e) {
					logger.error("Failed to create " + clazz, e);
				}
			}
		}

		return election;

	}

	/**********Leader ELection 29th March 2015******************************
	 * This function will call the run method of Raft Monitor class in the election instance. 
	 * In our case RaftElection --> Raft Monitor
	 */
	public void startMonitor() {		
		logger.info("Raft Monitor Started ");
		if (election == null)
			((RaftElection) electionInstance()).getMonitor().start();
			
			new Thread(new ClusterConnectionManager()).start();
	}
	
	public Integer whoIsTheLeader() {
		return this.leaderNode;
	}
	
	/** election listener implementation */
	public void concludeWith(boolean success, Integer leaderID) {
		if (success) {
			logger.info("----> the leader is " + leaderID);
			this.leaderNode = leaderID;
		}

		election.clear();
	}
	
	public void createLogs(String imageName)
	{
		LogMessage lm = ((RaftElection) electionInstance()).getLm();
		Integer logIndex = lm.getLogIndex();
		LinkedHashMap<Integer, String> entries = lm.getEntries();
		lm.setPrevLogIndex(logIndex);
		lm.setLogIndex(++logIndex);
		entries.put(logIndex,imageName);
		lm.setEntries(entries);
		((RaftElection) electionInstance()).setAppendLogs(true);
		
	}
	
	
//Setters and Getters	
	public static Logger getLogger() {
		return logger;
	}

	public static void setLogger(Logger logger) {
		RaftManager.logger = logger;
	}

	public static RaftManager getInstance() {
		return instance.get();
	}

	public static void setInstance(AtomicReference<RaftManager> instance) {
		RaftManager.instance = instance;
	}

	public static ServerConf getConf() {
		return conf;
	}

	public static void setConf(ServerConf conf) {
		RaftManager.conf = conf;
	}

	public static long getLastKnownBeat() {
		return lastKnownBeat;
	}

	public static void setLastKnownBeat(long lastKnownBeat) {
		RaftManager.lastKnownBeat = lastKnownBeat;
	}

	public int getFirstTime() {
		return firstTime;
	}

	public void setFirstTime(int firstTime) {
		this.firstTime = firstTime;
	}

	public Election getElection() {
		return election;
	}

	public void setElection(Election election) {
		this.election = election;
	}

	public int getElectionCycle() {
		return electionCycle;
	}

	public void setElectionCycle(int electionCycle) {
		this.electionCycle = electionCycle;
	}

	public Integer getSyncPt() {
		return syncPt;
	}

	public void setSyncPt(Integer syncPt) {
		this.syncPt = syncPt;
	}

	public Integer getLeaderNode() {
		return leaderNode;
	}

	public void setLeaderNode(Integer leaderNode) {
		this.leaderNode = leaderNode;
	}
	
	/***Once the leader is elected 
	 * This thread will continuosly send join message to all the nodes in the 
	 * Cluster conf file.
	***/
	public class ClusterConnectionManager extends Thread {
		private Map<Integer, Channel> connMap = new HashMap<Integer, Channel>();
		private Map<Integer, ClusterConf> clusterMap;

			public ClusterConnectionManager() {
				clusterMap = clusterConf.getClusters();
			}

			public void registerConnection(int nodeId, Channel channel) {
				connMap.put(nodeId, channel);
			}

			public ChannelFuture connect(String host, int port) {

				ChannelFuture channel = null;
				EventLoopGroup workerGroup = new NioEventLoopGroup();

				try {
					//logger.info("Attempting to  connect to : "+host+" : "+port);
					Bootstrap b = new Bootstrap();
					b.group(workerGroup).channel(NioSocketChannel.class)
							.handler(new ServerInitializer(false));

					b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
					b.option(ChannelOption.TCP_NODELAY, true);
					b.option(ChannelOption.SO_KEEPALIVE, true);

					channel = b.connect(host, port).syncUninterruptibly();
				} catch (Exception e) {
					//e.printStackTrace();
					return null;
				}

				return channel;
			}

			public Request createClusterJoinMessage(int fromCluster, int fromNode,
					int toCluster, int toNode) {
				//logger.info("Creating join message");
				Request.Builder req = Request.newBuilder();

				JoinMessage.Builder jm = JoinMessage.newBuilder();
				jm.setFromClusterId(fromCluster);
				jm.setFromNodeId(fromNode);
				jm.setToClusterId(toCluster);
				jm.setToNodeId(toNode);

				req.setJoinMessage(jm.build());
				return req.build();

			}

			@Override
			public void run() {
				Iterator<Integer> it = clusterMap.keySet().iterator();
				while (true) {
					if(whoIsTheLeader()!= null){
						try {
							int key = it.next();
							if (!connMap.containsKey(key)) {
								ClusterConf cc = clusterMap.get(key);
								List<NodeDesc> nodes = cc.getClusterNodes();
								//logger.info("For cluster "+ key +" nodes "+ nodes.size());
								for (NodeDesc n : nodes) {
									String host = n.getHost();
									int port = n.getPort();

									ChannelFuture channel = connect(host, port);
									Request req = createClusterJoinMessage(35325,
											conf.getNodeId(), key, n.getNodeId());
									if (channel != null) {
										channel = channel.channel().writeAndFlush(req);
										if (channel.channel().isWritable()) {
											registerConnection(key,
													channel.channel());
											logger.info("Connection to cluster " + key
													+ " added");
											break;
										}
									}
								}
							}
						} catch (NoSuchElementException e) {
							//logger.info("Restarting iterations");
							it = clusterMap.keySet().iterator();
							try {
								Thread.sleep(3000);
							} catch (InterruptedException e1) {
								// TODO Auto-generated catch block
								e1.printStackTrace();
							}
						}
					} else {
						try {
							Thread.sleep(3000);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}
			}
			
			
			public class ClusterLostListener implements ChannelFutureListener {
				ClusterConnectionManager ccm;

				public ClusterLostListener(ClusterConnectionManager ccm) {
					this.ccm = ccm;
				}

				@Override
				public void operationComplete(ChannelFuture future) throws Exception {
					logger.info("Cluster " + future.channel()
							+ " closed. Removing connection");
				}
			}
		}

	
}

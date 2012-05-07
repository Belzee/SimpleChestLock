package com.webkonsept.bukkit.simplechestlock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.webkonsept.bukkit.simplechestlock.listener.SCLBlockListener;
import com.webkonsept.bukkit.simplechestlock.listener.SCLEntityListener;
import com.webkonsept.bukkit.simplechestlock.listener.SCLPlayerListener;
import com.webkonsept.bukkit.simplechestlock.listener.SCLWorldListener;
import com.webkonsept.bukkit.simplechestlock.locks.LimitHandler;
import com.webkonsept.bukkit.simplechestlock.locks.SCLItem;
import com.webkonsept.bukkit.simplechestlock.locks.SCLList;
import com.webkonsept.bukkit.simplechestlock.locks.TrustHandler;

public class SCL extends JavaPlugin {
	private static final Logger log = Logger.getLogger("Minecraft");

    protected static final String pluginName = "SimpleChestLock";
    protected static String pluginVersion = "???";

    protected static boolean verbose = false;

    public Settings cfg = null;

	public final Messaging messaging = new Messaging(3000);
    public TrustHandler trustHandler;
    public LimitHandler limitHandler;

	protected Server server = null;
	
	// Intended to hold the material in question and a boolean of weather or not it's double-lockable (like a double chest)
	public final HashMap<Material,Boolean> lockable = new HashMap<Material,Boolean>();
	
	// Intended to hold the materials of items/blocked that can also be activated by left-click
	public final HashSet<Material> leftLocked = new HashSet<Material>();
	
	// Holding the valid locations for a multi-lockable block
	public final HashSet<Material> lockIncludeVertical = new HashSet<Material>();
	
	// Okay for the "sucks items" feature (Item containers only plx!)
	public final HashSet<Material> canSuck = new HashSet<Material>();
	
	// The "Lock as" feature!
	public final HashMap<String,String> locksAs = new HashMap<String,String>();
	
	private final SCLPlayerListener 	playerListener 	= new SCLPlayerListener(this);
	private final SCLBlockListener 	blockListener 	= new SCLBlockListener(this);
	private final SCLEntityListener 	entityListener 	= new SCLEntityListener(this);
	private final SCLWorldListener	worldListener	= new SCLWorldListener(this);
	public final SCLList			chests			= new SCLList(this);
	
	@Override
	public void onDisable() {
		chests.save("Chests.txt");
		// out("Disabled!"); // Bukkit already does this
		getServer().getScheduler().cancelTasks(this);
	}

	@Override
	public void onEnable() {
        pluginVersion = getDescription().getVersion();
        cfg = new Settings(this);
		setupLockables();
		
		trustHandler = new TrustHandler(this);
		limitHandler = new LimitHandler(this);

		server = getServer();
		chests.load("Chests.txt");
		PluginManager pm = getServer().getPluginManager();
		
		pm.registerEvents(playerListener,this);
		pm.registerEvents(blockListener,this);
		pm.registerEvents(entityListener,this);
		pm.registerEvents(worldListener,this);
		
		if (cfg.lockedChestsSuck()){
			server.getScheduler().scheduleSyncRepeatingTask(this,chests, cfg.suckInterval(), cfg.suckInterval());
		}
	}
	
	public boolean onCommand(CommandSender sender, Command command, String commandLabel, String[] args) {
		
		if ( ! this.isEnabled() ) return false;
		
		boolean success = false;
		boolean isPlayer = false;
		Player player = null;
		
		if (sender instanceof Player){
			isPlayer = true;
			player = (Player)sender;
		}

		if (command.getName().equalsIgnoreCase("scl")){
			if (args.length == 0){
				success = false;  // Automagically prints the usage.
			}
			else if (args.length >= 1){
				if (args[0].equalsIgnoreCase("reload")){
					success = true;  // This is a valid command.
					if ( !isPlayer  || permit(player, "simplechestlock.command.reload")){
                        cfg.load();
                        cfg.report(sender);
						String saveFile = "Chests.txt";
						if (args.length == 2){
							saveFile = args[1];
						}
						chests.load(saveFile);
						server.getScheduler().cancelTasks(this);
						if (cfg.lockedChestsSuck()){
							server.getScheduler().scheduleSyncRepeatingTask(this,chests, 100, 100);
						}
						sender.sendMessage(ChatColor.GREEN+"Successfully reloaded configuration and locks from "+saveFile);
						
					}
					else {
						sender.sendMessage(ChatColor.RED+"[SimpleChestLock] Sorry, permission denied!");
					}
				}
				else if (args[0].equalsIgnoreCase("as")){
					success = true;
					if (args.length == 2){
						if (!isPlayer){
							sender.sendMessage("Sorry mr. Console, you can't lock as anyone.  How will you swing the stick?");
						}
						else if (permit(player, "simplechestlock.command.as")){
							locksAs.put(player.getName(),args[1]);
							sender.sendMessage(ChatColor.RED+"[SimpleChestLock] Locking chests for "+args[1]);
						}
						else {
							sender.sendMessage(ChatColor.RED+"[SimpleChestLock] Sorry, permission denied!");
						}
					}
					else if (args.length == 1){
						if (locksAs.containsKey(player.getName())){
							locksAs.remove(player.getName());
						}
						sender.sendMessage(ChatColor.GREEN+"[SimpleChestLock] Locking chests for yourself");
					}
					else if (args.length > 2){
						sender.sendMessage(ChatColor.YELLOW+"[SimpleChestLock] Argument amount mismatch.  /scl as <name>");
					}
				}
				else if (args[0].equalsIgnoreCase("save")){
					success = true;
					if (!isPlayer || permit(player,"simplechestlock.command.save")){
						String saveFile = "Chests.txt";
						if (args.length == 2){
							saveFile = args[1];
						}
						chests.save(saveFile);
						sender.sendMessage(ChatColor.GREEN+"Saved locks to "+saveFile);
					}
					else {
						sender.sendMessage(ChatColor.RED+"[SimpleChestLock] Sorry, permission denied!");
					}
				}
				else if (args[0].equalsIgnoreCase("limit")){
				    success = true;
				    if (!isPlayer){
				        sender.sendMessage("Mr. Console, you can't lock anything at all, so your limit is -1!");
				    }
				    else if (!cfg.useLimits()){
				        sender.sendMessage(ChatColor.GOLD+"This server has no lock limits");
				    }
				    else if (permit(player,"simplechestlock.nolimit")){
				        sender.sendMessage(ChatColor.GOLD+"You are excempt from lock limits");
				    }
				    else {
				        sender.sendMessage(ChatColor.GREEN+limitHandler.usedString(player));
				    }
				}
				else if (args[0].equalsIgnoreCase("status")){
					success = true;
					if (!isPlayer || permit(player,"simplechestlock.command.status")){
						HashMap<String,Integer> ownership = new HashMap<String,Integer>();
						int total = 0;
						for (SCLItem item : chests.list.values()){
							Integer owned = ownership.get(item.getOwner());
							total++;
							if (owned == null){
								ownership.put(item.getOwner(),1);
							}
							else {
								ownership.put(item.getOwner(),++owned);
							}
						}
						
						for (String playerName : ownership.keySet()){
							Integer owned = ownership.get(playerName);
							if (owned == null){
								owned = 0;
							}
							sender.sendMessage(playerName+": "+owned);
						}
						sender.sendMessage("Total: "+total);
					}
				}
				else if (args[0].equalsIgnoreCase("trust")){
				    if (isPlayer){
				        if (permit(player,"simplechestlock.command.trust")){
				            trustHandler.parseCommand(player,args);
				        }
				    }
				    success = true;
				}
				else if (args[0].equalsIgnoreCase("list")){
					success = true;
					if (!isPlayer || permit(player, "simplechestlock.command.list")){
						for (SCLItem item : chests.list.values()){
							sender.sendMessage(item.getLocation().toString());
						}
					}
				}
				else if (args[0].equalsIgnoreCase("getkey")){
				    success = true;
				    if (isPlayer){
				        if (permit(player, "simplechestlock.command.getkey")){
				            player.getInventory().addItem(cfg.key().clone());
				            player.sendMessage(ChatColor.GREEN+"One key coming right up!");
				        }
				        else {
				            player.sendMessage(ChatColor.RED+"Access denied");
				        }
				    }
				    else {
				        sender.sendMessage("Sorry, Mr. Console, you can't carry keys.");
				    }
				}
				else if (args[0].equalsIgnoreCase("getcombokey")){
				    success = true;
                    if (isPlayer){
                        if (permit(player, "simplechestlock.command.getcombokey")){
                            player.getInventory().addItem(cfg.comboKey().clone());
                            player.sendMessage(ChatColor.GREEN+"One combokey coming right up!");
                        }
                        else {
                            player.sendMessage(ChatColor.RED+"Access denied");
                        }
                    }
                    else {
                        sender.sendMessage("Sorry, Mr. Console, you can't carry keys.");
                    }				    
				}
			}
		}
		else {
			sender.sendMessage(ChatColor.RED+"Command is deprecated, try /scl");
		}
		return success;
	}
	public static boolean permit(Player player,String[] permissions){
		
		if (player == null) return false;
		if (permissions == null) return false;
		String playerName = player.getName();
		boolean permit = false;
		for (String permission : permissions){
			permit = player.hasPermission(permission);
			if (permit){
				verbose("Permission granted: " + playerName + "->(" + permission + ")");
				break;
			}
			else {
				verbose("Permission denied: " + playerName + "->(" + permission + ")");
			}
		}
		return permit;
		
	}
	public static boolean permit(Player player,String permission){
		return permit(player,new String[]{permission});
	}
	public static void out(String message) {
        log.info("[" + pluginName + " v" + pluginVersion + "] " + message);
	}
	public static void crap(String message){
        log.severe("[" + pluginName + " v" + pluginVersion + "] " + message);
	}
	public static void verbose(String message){
		if (!verbose){ return; }
		log.info("[" + pluginName + " v" + pluginVersion + " VERBOSE] " + message);
	}
	public static String plural(int number) {
		if (number == 1){
			return "";
		}
		else {
			return "s";
		}
	}
	private void setupLockables() {
		lockable.clear();
		// DEFAULT VALUES
		lockable.put(Material.CHEST,true);
		lockable.put(Material.DISPENSER,false);
		lockable.put(Material.JUKEBOX,false);
		lockable.put(Material.ENCHANTMENT_TABLE,false);
		lockable.put(Material.BREWING_STAND,false);
		
		// Requested to be lockable, even though it can't store stuff
		lockable.put(Material.WORKBENCH,false);
		
		//NOTE:  If double locking is enabled for furnaces, remember that a furnace and a burning furnace are NOT the same material!
		// That means that double locking, which tests the neighboring blocks for .equals() on the material, won't work if one is burning. 
		lockable.put(Material.FURNACE,false);
		lockable.put(Material.BURNING_FURNACE,false);
		
		// Levers, buttons and doors are special:  You can activate them with a left click.
		// Hence, we have to lock interaction via LMB as well, making them "leftLocked"
		
		lockable.put(Material.LEVER,false);
		leftLocked.add(Material.LEVER);
		lockable.put(Material.STONE_BUTTON,false);
		leftLocked.add(Material.STONE_BUTTON);
		lockable.put(Material.TRAP_DOOR, false);
		leftLocked.add(Material.TRAP_DOOR);
		
		// WTH, this doesn't seem to work?!
		lockable.put(Material.FENCE_GATE, false);
		leftLocked.add(Material.FENCE_GATE);
		
		// And now:  Pressure plates!
		lockable.put(Material.STONE_PLATE,false);
		lockable.put(Material.WOOD_PLATE,false);
		
		// Doors are lockable, leftLocked AND vertically speaking TWO blocks.
		// This makes them rather complex to lock...
		lockable.put(Material.WOODEN_DOOR, true);
		leftLocked.add(Material.WOODEN_DOOR);
		lockIncludeVertical.add(Material.WOODEN_DOOR);
		
		
		// Some types will "suck" in items, if enabled
		canSuck.add(Material.CHEST);
		canSuck.add(Material.DISPENSER);
		
		
		// The associated permissions
		verbose("Preparing permissions:");
	    Permission allBlocksPermission = new Permission("simplechestlock.locktype.*");
        for (Material mat : lockable.keySet()){
            if (mat.isBlock()){
                String permissionName = "simplechestlock.locktype."+mat.toString().toLowerCase();
                verbose("   -> Preparing permission " + permissionName);
                Permission thisBlockPermission = new Permission(permissionName,PermissionDefault.OP);
                //getServer().getPluginManager().addPermission(allBlocksPermission);
                thisBlockPermission.addParent(allBlocksPermission, true);
            }
        }
        getServer().getPluginManager().addPermission(allBlocksPermission);
	}
	public boolean canLock (Block block){
		if (block == null) return false;
		Material material = block.getType();
		return lockable.containsKey(material);
	}
	public boolean canDoubleLock (Block block){
		if (block == null) return false;
		Material material = block.getType();
		if (lockable.containsKey(material)){
			return lockable.get(material);
		}
		else {
			return false;
		}
	}

	public boolean toolMatch (ItemStack candidate1,ItemStack candidate2){
	    if (candidate1 == null || candidate2 == null){
	        return false;
	    }
	    else return candidate1.getType().equals(candidate2.getType())
                && candidate1.getData().equals(candidate2.getData());
	}
}

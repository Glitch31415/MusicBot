/*
 * Copyright 2016 John Grosh (jagrosh).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot;

import ch.qos.logback.classic.Level;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jagrosh.jdautilities.command.CommandClient;
import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import com.jagrosh.jmusicbot.commands.CommandFactory;
import com.jagrosh.jmusicbot.entities.Prompt;
import com.jagrosh.jmusicbot.entities.UserInteraction;
import com.jagrosh.jmusicbot.gui.GUI;
import com.jagrosh.jmusicbot.settings.SettingsManager;
import com.jagrosh.jmusicbot.utils.ConsoleUtil;
import com.jagrosh.jmusicbot.utils.InstanceLock;
import com.jagrosh.jmusicbot.utils.OtherUtil;

import net.dv8tion.jda.api.JDA;
import org.json.*;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublisher;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.utils.FileUpload;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.apache.commons.io.FileUtils;
import javax.sound.sampled.*;

/**
 *
 * @author John Grosh (jagrosh)
 */
public class JMusicBot extends ListenerAdapter
{
    int headerlength = 0;
	static int delay = 10800;
	static boolean ondemand = true;
    static HttpClient client = HttpClient.newHttpClient();
    static Guild guild = null;
    static JDA jda = null;
	static int imgcounter = 0;
    public final static Logger LOG = LoggerFactory.getLogger(JMusicBot.class);
    public final static Permission[] RECOMMENDED_PERMS = {
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_ADD_REACTION,
            Permission.MESSAGE_EMBED_LINKS,
            Permission.MESSAGE_ATTACH_FILES,
            Permission.MESSAGE_MANAGE,
            Permission.MESSAGE_EXT_EMOJI,
            Permission.VOICE_CONNECT,
            Permission.VOICE_SPEAK,
            Permission.NICKNAME_CHANGE
    };
    public final static GatewayIntent[] INTENTS = {
            GatewayIntent.DIRECT_MESSAGES,
            GatewayIntent.GUILD_MESSAGES,
            GatewayIntent.GUILD_MESSAGE_REACTIONS,
            GatewayIntent.GUILD_VOICE_STATES,
            GatewayIntent.MESSAGE_CONTENT
    };
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args)
    {
        if(args.length > 0) {
            if (args[0].equalsIgnoreCase("generate-config")) {
                BotConfig.writeDefaultConfig();
                return;
            }
        }
        startBot();
    }
    
    private static void startBot()
    {
        // create user interaction handler for startup
        UserInteraction userInteraction = new Prompt("JMusicBot");
        
        // Redirect System.out/err to GUI console early (before config loading)
        // so that all logs, including those from config loading, appear in GUI
        if(!userInteraction.isNoGUI())
        {
            try 
            {
                ConsoleUtil.redirectSystemStreams();
            }
            catch(Exception e)
            {
                LOG.warn("Could not redirect console streams to GUI. Logs may not appear in GUI console.");
            }
        }
        
        // Check for another running instance
        if (!InstanceLock.tryAcquire()) {
            userInteraction.alert(Prompt.Level.ERROR, "JMusicBot",
                    "Another instance of JMusicBot is already running.\n" +
                    "Running multiple instances with the same configuration causes duplicate responses to commands.\n" +
                    "Please close the other instance first.");
            System.exit(1);
        }
        
        // startup checks
        OtherUtil.checkVersion(userInteraction);
        OtherUtil.checkJavaVersion(userInteraction);
        
        // load config
        BotConfig config = new BotConfig(userInteraction);
        config.load();
        if(!config.isValid())
            return;
        LOG.info("Loaded config from {}", config.getConfigLocation());

        // set log level from config
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(
                Level.toLevel(config.getLogLevel(), Level.INFO));
        
        // set up the listener
        EventWaiter waiter = new EventWaiter();
        SettingsManager settings = new SettingsManager();
        Bot bot = new Bot(waiter, config, settings);
        
        // Initialize GUI (ConsolePanel will reuse the already-redirected streams)
        if(!userInteraction.isNoGUI())
        {
            try 
            {
                GUI gui = new GUI(bot);
                bot.setGUI(gui);
                gui.init();
            }
            catch(Exception e)
            {
                LOG.error("Could not start GUI. Use -Dnogui=true for server environments.");
            }
        }
        
        CommandClient client = CommandFactory.createCommandClient(config, settings, bot);

        // Now that GUI/Logging is ready, initialize the player manager
        bot.getPlayerManager().init();

        // attempt to log in and start
        try
        {
            jda = DiscordService.createJDA(config, bot, waiter, client, userInteraction);
            bot.setJDA(jda);
        }
        catch(IllegalArgumentException ex)
        {
            userInteraction.alert(Prompt.Level.ERROR, "JMusicBot",
                    "Invalid configuration. Check your token.\nConfig Location: " + config.getConfigLocation());
            System.exit(1);
        }
        catch(ErrorResponseException ex)
        {
            userInteraction.alert(Prompt.Level.ERROR, "JMusicBot", "Invalid response from Discord. Check your internet connection.");
            System.exit(1);
        }
        catch(Exception ex)
        {
            LOG.error("An unexpected error occurred during startup", ex);
            System.exit(1);
        }
        long lastchecked = 0;
		jda.addEventListener(new JMusicBot());
		//sendporn();
        while (0==0) {
        	
        	// Source - https://stackoverflow.com/a/20117216
        	// Posted by srain, modified by community. See post 'Timeline' for change history
        	// Retrieved 2026-03-25, License - CC BY-SA 4.0
        	
        	// Source - https://stackoverflow.com/a/64669662
        	// Posted by david-so, modified by community. See post 'Timeline' for change history
        	// Retrieved 2026-03-28, License - CC BY-SA 4.0
        	
        	try {
        		if (System.currentTimeMillis() > lastchecked + 60000) {
        			//guild.addRoleToMember(jda.retrieveUserById(1252981607590006859L).complete(),jda.getRoleById(1472968735529631775L)).queue();
        			lastchecked = System.currentTimeMillis();
        		}
        	
        	} catch (Exception e) {
        		System.out.println(e.toString());
        	}

        	if (ondemand == false) {
        		sendporn();
        	}
        	try {
        		if (ondemand == false) {
        			int counter = 0;
            		while (counter < delay) {
            			Thread.sleep(1000);
            			try {
            				if (System.currentTimeMillis() > lastchecked + 60000) {
                    			//guild.addRoleToMember(jda.retrieveUserById(1252981607590006859L).complete(),jda.getRoleById(1472968735529631775L)).queue();
                    			lastchecked = System.currentTimeMillis();
                    		}
            	        	} catch (Exception e) {
            	        		System.out.println(e.toString());
            	        	}
            			counter = counter + 1;
            		}
        		}
        		else {
        			//Thread.sleep(1000);
					try {
						// Source - https://stackoverflow.com/a/39629699
// Posted by passion, modified by community. See post 'Timeline' for change history
// Retrieved 2026-04-07, License - CC BY-SA 3.0

File[] filesp = new File("/home/glitch/hlcoop-sfx/porn/").listFiles();
File filep = filesp[imgcounter];

					jda.getTextChannelById(1472961860805333024L).sendFiles(FileUpload.fromData(filep)).queue();
					} catch (Exception fuck) {
						if (!fuck.toString().contains("open files")) {
							imgcounter = 0;
						}
						System.out.println(fuck.toString());
					}
					System.out.println(imgcounter);
					imgcounter = imgcounter + 1;
        		}
        		
				
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
    }
    public static void sendporn() {
		boolean sent = false;
		String filename = "";
    	while (sent == false) {
    		try {
    			boolean goodshit = false;
            	String thingurl = "";
    	while (goodshit == false) {
    		
    		HttpRequest request = HttpRequest.newBuilder()
            		  .uri(URI.create("https://e621.net/posts/random.json?tags=-female%20-scat%20-gore%20-death%20-diaper%20-gynomorph%20-trans%20-andromorph%20-deltarune%20score%3A%3E999"))
            		  .GET()
            		  //.POST(body)
            		  .build();
          	HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
          	if (response.statusCode() == 200) {
          		JSONObject obj = new JSONObject(response.body());
          		System.out.println(obj.getJSONObject("post").getString("rating"));
          		if (obj.getJSONObject("post").getString("rating").contains("e") && obj.getJSONObject("post").getJSONObject("score").getInt("total") > 999) {
          			thingurl = obj.getJSONObject("post").getJSONObject("file").getString("url");
          			goodshit = true;
          		}
          		else {
          			System.out.println("not good enough");
          		}
          	}
          	else {
          		System.out.println("error: " + response.body());
          	}
    		//Thread.sleep(2000);
    	}
    	System.out.println("good:");
    	System.out.println(thingurl);
    	filename = thingurl.replaceAll("[^a-zA-Z0-9.]", "");

    	InputStream in;
		in = new URL(thingurl).openStream();
		Files.copy(in, Paths.get("/home/glitch/hlcoop-sfx/porn/" + filename), StandardCopyOption.REPLACE_EXISTING);
		if (new File("/home/glitch/hlcoop-sfx/" + filename).length() < 10000000) {
			sent = true;
			guild = jda.getTextChannelById(1472961860805333024L).getGuild();
			jda.getTextChannelById(1472961860805333024L).sendFiles(FileUpload.fromData(new File("/home/glitch/hlcoop-sfx/porn/" + filename))).queue();
		}
		else {
			System.out.println("too big");
		}
		//new File("/home/glitch/hlcoop-sfx/" + filename).delete();
    	} catch (Exception e) {
			System.out.println(e.toString());
			//new File("/home/glitch/hlcoop-sfx/" + filename).delete();
			sent = false;
		}
    	}
	}
	public static boolean isInteger(String s) {
	    try { 
	        Integer.parseInt(s); 
	    } catch(NumberFormatException e) { 
	        return false; 
	    } catch(NullPointerException e) {
	        return false;
	    }
	    // only got here if we didn't return false
	    return true;
	}
	public void volume(String msg) throws IOException {
		//System.out.println(headerlength);
		byte[] data = FileUtils.readFileToByteArray(new File("/home/glitch/hlcoop-sfx/" + msg.toLowerCase() + ".wav"));
		byte[] newdata = new byte[data.length];
		int byteindex = 0;
		while (byteindex < (data.length)) {
			if (byteindex < headerlength) {
				newdata[byteindex] = data[byteindex];
			}
			else {
				//int resbyte = (int)data[byteindex];
				//resbyte = resbyte * 2;
				//if (resbyte > 127) {
					//resbyte = 127;
				//}
				//if (resbyte < -128) {
					//resbyte = -128;
				//}
				//System.out.println((int)data[byteindex]);
				//System.out.println(resbyte);
				newdata[byteindex] = (byte)(((int)data[byteindex])*2);
			}
			
			byteindex = byteindex + 1;
		}
		// Source - https://stackoverflow.com/a/4350109
		// Posted by bmargulies, modified by community. See post 'Timeline' for change history
		// Retrieved 2026-02-24, License - CC BY-SA 3.0

		FileUtils.writeByteArrayToFile(new File("/home/glitch/hlcoop-sfx/" + msg.toLowerCase() + "-volume.wav"), newdata);
	}
	// Source - https://stackoverflow.com/a/11741948
	// Posted by AlexR
	// Retrieved 2026-02-24, License - CC BY-SA 3.0



    public void onMessageReceived(MessageReceivedEvent event)
    {
    	
    	try {
        //if (event.isFromType(ChannelType.PRIVATE))
        //{
            //System.out.printf("[PM] %s: %s\n", event.getAuthor().getName(),
                                    //event.getMessage().getContentDisplay());
        //}
        //else
        //{
            //System.out.printf("[%s][%s] %s: %s\n", event.getGuild().getName(),
                        //event.getChannel().getName(), event.getMember().getEffectiveName(),
                        //event.getMessage().getContentDisplay());
        //}
		if (event.getMember().getId().equals("1413723386772979805")) {
    		event.getMessage().delete().queue();
    	}
    	if (!event.getMember().getId().equals("1489777457606299779")) {
    		String[] message = event.getMessage().getContentDisplay().split(" ");
    		if (event.getMember().getId().equals("998736610436857926")) {
    			if (message.length > 1 && isInteger(message[1]) && message[0].equals("delay")) {
    				if (Integer.parseInt(message[1]) < 0) {
    					ondemand = true;
    				} else {
    					ondemand = false;
    					delay = Integer.parseInt(message[1]);
    				}
        			
        		}
    		}
    		if (event.getChannel().getIdLong() == 1472961860805333024L && ondemand == true && !event.getMember().getId().equals("815328232537718794")) {
    			// edit messages when necessary
    			int attachmentcounter = 0;
    			if (event.getMessage().getContentRaw().contains("http://") || event.getMessage().getContentRaw().contains("https://")) {
    				attachmentcounter = -1;
    			}
    			if (event.getMessage().getMessageSnapshots() != null && !event.getMessage().getMessageSnapshots().isEmpty()) {
    				attachmentcounter = -1 * event.getMessage().getMessageSnapshots().size();
    			}
    			if (event.getMessage().getEmbeds() != null && !event.getMessage().getEmbeds().isEmpty()) {
    				attachmentcounter = -1 * event.getMessage().getEmbeds().size();
    			}
    			
    			while (attachmentcounter < event.getMessage().getAttachments().size()) {
    				System.out.println(attachmentcounter);
    				sendporn();
    				attachmentcounter = attachmentcounter + 1;
    			}
    		}
    		//event.getChannel().sendMessage(event.getMessage().getContentDisplay()).queue();
    		
    		
    		//if ((message.length == 1) || (message.length == 2 && isInteger(message[1]))) {
    			int pitch = 100;
        		boolean volume = message[0].equals(message[0].toUpperCase());
        		if (message.length > 1 && isInteger(message[1])) {
        			pitch = Integer.parseInt(message[1]);
        			if (pitch > 250) {
        				pitch = 250;
        			}
        			if (pitch < 25) {
        				pitch = 25;
        			}
        		}
        		if (!new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + ".wav").exists() && !new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + ".none").exists()) {
    				try {
    					InputStream in;
						in = new URL("http://w00tguy.ddns.net/sound/csound/" + message[0].toLowerCase().replace("?", "").replace(".", "") + ".wav").openStream();
						Files.copy(in, Paths.get("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + ".wav"), StandardCopyOption.REPLACE_EXISTING);
    				} catch (FileNotFoundException e) {
    					//System.out.println(message[0].toLowerCase());
    					try {
    					 new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + ".none").createNewFile();
    					} catch (Exception e2) {
    						System.out.println(message[0]);
    						System.out.println(e2.toString());
    						
    					}
    					 
    				}
        		}
        		if (!new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + ".none").exists()) {
        			if (volume == true) {
        				//System.out.println("test");

        				//File sfxfile = new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + ".wav");
        				// Source - https://stackoverflow.com/a/11741948
        				// Posted by AlexR
        				// Retrieved 2026-02-24, License - CC BY-SA 3.0

        				if (!new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + "-volume.wav").exists()) {
        					//headerlength = 0;
        					boolean worked = false;
            				//headerlength = 0;
            				while (worked == false) {
            					try {
                    				AudioInputStream astream = AudioSystem.getAudioInputStream(new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + "-volume.wav"));
                    				worked = true;
                    				} catch (Exception e) {
                    					headerlength = headerlength + 1;
                    					//System.out.println(headerlength);
                    					volume(message[0]);
                    				}
            				}
        				}

        				//sfxfile.
        				
        				

        				
        				
        				
        			}
        			if (pitch != 100) {
        				// Source - https://stackoverflow.com/a/26060297
        				// Posted by Hendrik
        				// Retrieved 2026-02-21, License - CC BY-SA 3.0

        				

        				//try {
        				    //Clip clip = AudioSystem.getClip();
        				    //AudioInputStream ulawIn = AudioSystem.getAudioInputStream(new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + ".wav"));

        				    // define a target AudioFormat that is likely to be supported by your audio hardware,
        				    // i.e. 44.1kHz sampling rate and 16 bit samples.
        				    //AudioInputStream astream = AudioSystem.getAudioInputStream(
        				            //new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, 1, 2, 44100f, true),
        				            //AudioSystem.getAudioInputStream(new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + ".wav")));

        				    //clip.open(pcmIn);
        				    //clip.start(); 
        				//} catch (Exception e) {
        				    //System.err.println(e.getMessage());
        				//}
        				File sfxfile = null;
        				if (volume == true) {
        					sfxfile = new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + "-volume.wav");
        				}
        				else {
        					sfxfile = new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + ".wav");
        				}
        				
        				AudioInputStream astream = null;
        				boolean worked = false;
        				//headerlength = 0;
        				while (worked == false) {
        					try {
                				astream = AudioSystem.getAudioInputStream(sfxfile);
                				worked = true;
                				} catch (Exception e) {
                					headerlength = headerlength + 1;
                					//System.out.println(headerlength);
                					volume(message[0]);
                				}
        				}
        				
        				//System.out.println((float)astream.getFormat().getSampleRate()*(float)((float)pitch/(float)100));
        				AudioInputStream astream2 = new AudioInputStream(
        				    astream,
        				    new AudioFormat(astream.getFormat().getEncoding(),
        				    		(float)astream.getFormat().getSampleRate()*(float)((float)pitch/(float)100),
        				        astream.getFormat().getSampleSizeInBits(),
        				        astream.getFormat().getChannels(),
        				        astream.getFormat().getFrameSize(),
        				        astream.getFormat().getFrameRate(),
        				        astream.getFormat().isBigEndian()
        				   ),
        				    astream.getFrameLength());
        				//System.out.println(astream.getFormat().getSampleRate());
        				//System.out.println(astream2.getFormat().getSampleRate());
        				
        				    //try {
        				        //InputStream br = new InputStream(input.getInputStream());
        				        //FileWriter fOut= new FileWriter("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + "-pitched.wav");
        				        //BufferedWriter out = new BufferedWriter(fOut);
        				        //String line = br.
        				    	// Source - https://stackoverflow.com/a/4350109
        				    	// Posted by bmargulies, modified by community. See post 'Timeline' for change history
        				    	// Retrieved 2026-02-21, License - CC BY-SA 3.0
        				    	AudioSystem.write(astream2, AudioFileFormat.Type.WAVE,new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + "-pitched.wav"));
        				    	//FileUtils.writeByteArrayToFile(new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + "-pitched.wav"), astream2.readAllBytes());

        				        //Files.copy(
        				        	      //astream2, 
        				        	      //new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + "-pitched.wav").toPath(), 
        				        	      //StandardCopyOption.REPLACE_EXISTING);

        				    //} catch (IOException e) {
        				        // TODO Auto-generated catch block
        				        //e.printStackTrace();
        				    //}
        				    event.getChannel().sendFiles(FileUpload.fromData(new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + "-pitched.wav"))).queue();
        				    astream.close();
        				    astream2.close();
        				    new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + "-pitched.wav").delete();
        				    //if (volume == true) {
        				    	//new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + "-volume.wav").delete();
        				    //}
        				    
        				    


        			}
        			else {
        				if (volume == true) {
        					event.getChannel().sendFiles(FileUpload.fromData(new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + "-volume.wav"))).queue();
        					//new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + "-volume.wav").delete();
        				}
        				else {
        					event.getChannel().sendFiles(FileUpload.fromData(new File("/home/glitch/hlcoop-sfx/" + message[0].toLowerCase() + ".wav"))).queue();
        				}
        				
        			}
        			
        			//event.getChannel().sendMessage("pitch: " + pitch + ", volume: " + volume).queue();
        		}
    		//}
    		
    	}
    	} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	
    }
    public void onMessageDelete(MessageDeleteEvent event)
    {
    	if (event.getChannel().getIdLong() == 1472961860805333024L && ondemand == true) {
    		sendporn();
    	}
    }
    public void onMessageUpdate(MessageUpdateEvent event)
    {
    	if (event.getChannel().getIdLong() == 1472961860805333024L && ondemand == true) {
    		sendporn();
    	}
    }

}

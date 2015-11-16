package org.maxgamer.rs.network.io.rawhandler;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;

import org.maxgamer.rs.cache.XTEAKey;
import org.maxgamer.rs.core.Core;
import org.maxgamer.rs.io.InputStreamWrapper;
import org.maxgamer.rs.lib.log.Log;
import org.maxgamer.rs.model.entity.mob.persona.player.ScreenSettings;
import org.maxgamer.rs.network.AuthResult;
import org.maxgamer.rs.network.Session;
import org.maxgamer.rs.network.io.stream.RSByteBuffer;

/**
 * @author netherfoam
 */
public class LoginRequestHandler extends RawHandler {
	private static BigInteger RSA_MODULUS;
	private static BigInteger RSA_EXPONENT;
	
	static{
		String priv = Core.getWorldConfig().getString("rsa.private-key");
		if(priv.startsWith("0x") || priv.startsWith("0X")){
			RSA_MODULUS = new BigInteger(priv, 16);
		}
		else{
			RSA_MODULUS = new BigInteger(priv, 10);
		}
		
		String exp = Core.getWorldConfig().getString("rsa.private-exponent");
		if(priv.startsWith("0x") || priv.startsWith("0X")){
			RSA_EXPONENT = new BigInteger(exp, 16);
		}
		else{
			RSA_EXPONENT = new BigInteger(exp, 10);
		}
		
		System.out.println("Exponent: " + RSA_EXPONENT);
		System.out.println("Modulus: " + RSA_MODULUS);
	}
	
	public LoginRequestHandler(Session s) {
		super(s);
	}
	
	@Override
	public void handle(RSByteBuffer buffer) {
		if (Core.getServer().getLogon().isConnected() == false) {
			try {
				getSession().write(AuthResult.LOGIN_SERVER_OFFLINE.getCode());
				Log.info("LoginServer is offline, so login request has been declined.");
			}
			finally {
				getSession().close(true);
			}
		}
		
		InputStreamWrapper in = null;
		String name = null;
		String pass = null;
		int uuid = -1;
		boolean toLobby = false;
		
		try {
			int opcode = buffer.readByte() & 0xFF;
			
			//Length of data available. (~280 ish) - Packet size.
			int packetLength = buffer.readShort(); //Number of bytes remaining
			int version = buffer.readInt(); //Client version
			getSession().setRevision(version);
			
			byte[] rsaPayload = new byte[(buffer.readShort() & 0xFFFF)];
			buffer.read(rsaPayload);
			
			RSByteBuffer rsaEncrypted = new RSByteBuffer(ByteBuffer.wrap(new BigInteger(rsaPayload).modPow(RSA_EXPONENT, RSA_MODULUS).toByteArray()));
			
			int rsaHeader = rsaEncrypted.readByte();
			if (rsaHeader != 10) {
				//We tried with our RSA key, but it appears the key didn't work.
				//We try again without an RSA key, in case the client has it disabled.
				rsaEncrypted = new RSByteBuffer(ByteBuffer.wrap(rsaPayload));
				
				rsaHeader = rsaEncrypted.readByte();
				
				if(rsaHeader != 10){
					Log.warning("Invalid RSA Header: " + rsaHeader + ", length: " + packetLength);
					Log.warning("This may indicate that the client is using a different RSA key, or the protocol handling is incorrect");
					Log.warning("Dropping connection from " + getSession().getIP().getHostName());
					getSession().close(false);
					return;
				}
			}
			
			//Client seed?
			int[] keys = new int[4];
			for (int i = 0; i < keys.length; i++) {
				keys[i] = rsaEncrypted.readInt();
			}
			XTEAKey key = new XTEAKey(keys);
			
			rsaEncrypted.readLong(); //Appears to be zero always
			
			pass = rsaEncrypted.readPJStr1();
			
			//Client UUID
			rsaEncrypted.readLong(); // client key, appears to be 0 always
			rsaEncrypted.readInt(); // always 0
			
			uuid = rsaEncrypted.readInt(); // other client key, randomly generated every time client starts
			
			//The rest of the packet is encrypted
			byte[] block = new byte[packetLength - rsaPayload.length - 6];
			buffer.read(block);
			
			//Decrypt it
			ByteBuffer bb = ByteBuffer.wrap(block);
			key.decipher(bb, 0, block.length);
			
			//A nice way of reading.
			in = new InputStreamWrapper(block);
			name = in.readString();
			
			if (opcode == 16 || opcode == 18) { //Initial world join or resume (rejoin) request
				toLobby = false;
				in.readByte(); //Unknown..
				
				//Screen settings
				int mode = in.read();
				int width = in.readShort();
				int height = in.readShort();
				boolean active = in.readByte() != 0; //is window selected, I assume.
				
				ScreenSettings ss = getSession().getScreenSettings();
				ss.setDisplayMode(mode);
				ss.setWidth(width);
				ss.setHeight(height);
				ss.setWindowActive(active);
				
				for (int i = 0; i < 24; i++) {
					in.readByte();
				}
				in.readString(); //Settings
				
				in.readInt();
				for (int i = 0; i < 34; i++) {
					in.readInt();
				}
			}
			else if (opcode == 19) {
				toLobby = true;
				//LOBBY
				
				in.readByte(); // screen settings?
				in.readByte();
				for (int i = 0; i < 24; i++) {
					in.readByte();
				}
				
				in.readInt();
				for (int i = 0; i < 34; i++) {
					in.readInt();
				}
				
				//We are left with 4 unknown bytes. On my client they are (in hex) (0x24, 0x57, 0x42, 0x5C)
				while (in.available() > 0) {
					in.readByte();
				}
			}
			else {
				throw new IOException();
			}
		}
		catch (IOException e) {
			//This is caused by the player disconnecting during the login process.
			getSession().close(false);
			return;
		}
		finally {
			if (in != null) in.close();
		}
		
		//if(Core.getServer().getLogon().request(request) == false){
		Core.getServer().getLogon().getAPI().authenticate(getSession(), name, pass, uuid, toLobby);
		/*
		 * Log.info(
		 * "LoginServer has not yet responded to previous join request, so the request has been declined."
		 * ); try{ getSession().write(AuthResult.SYSTEM_UNAVAILABLE.getCode());
		 * } finally{ getSession().close(true); }
		 */
	}
}
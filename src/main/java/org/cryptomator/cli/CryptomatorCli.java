/*******************************************************************************
 * Copyright (c) 2016 Sebastian Stenzel and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the accompanying LICENSE.txt.
 *
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 *******************************************************************************/
package org.cryptomator.cli;

import org.cryptomator.cli.frontend.FuseMount;
import org.cryptomator.cli.frontend.WebDav;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.cli.ParseException;
import org.cryptomator.cryptofs.CryptoFileSystemProperties;
import org.cryptomator.cryptofs.CryptoFileSystemProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class mountInfo{
	mountInfo(String vp,String mp) {
		this.vaultPath = decode(vp);
		this.mountPath = decode(mp);
	}
	private String decode(String s) {
		String m = null;
		try{
			m = s.replace("\\012","\n");
			m = m.replace("\\040"," " );
			m = m.replace("\\134","\\" );
			m = m.replace("\\011","\\t" );
		} catch(java.lang.NullPointerException e){}

		if (m==null){
			return s;
		}else{
			return m;
		}
	}
	public final String vaultPath;
	public final String mountPath;
}

public class CryptomatorCli {

	private static final Logger LOG = LoggerFactory.getLogger(CryptomatorCli.class);

	public static void main(String[] rawArgs) throws IOException {
		try {
			if (rawArgs.length==1 && strEq(rawArgs[0], "--version")) {

				new CryptomatorCli().printVersion();
			}else{
				Args args = Args.parse(update(rawArgs));
				validate(args);
				startup(args);
			}
		} catch (ParseException e) {
			LOG.error("Invalid or missing arguments", e);
			Args.printUsage();
		} catch (IllegalArgumentException e) {
			LOG.error(e.getMessage());
			Args.printUsage();
		}
	}

	private static void validate(Args args) throws IllegalArgumentException {
		Set<String> vaultNames = args.getVaultNames();
		if (args.hasValidWebDavConf() && (args.getPort() < 0 || args.getPort() > 65536)) {
			throw new IllegalArgumentException("Invalid WebDAV Port.");
		}

		if (vaultNames.size() == 0) {
			throw new IllegalArgumentException("No vault specified.");
		}

		for (String vaultName : vaultNames) {
			Path vaultPath = Paths.get(args.getVaultPath(vaultName));
			if (!Files.isDirectory(vaultPath)) {
				throw new IllegalArgumentException("Not a directory: " + vaultPath);
			}
			args.addPasswortStrategy(vaultName).validate();

			Path mountPoint = args.getFuseMountPoint(vaultName);
			if (mountPoint != null && !Files.isDirectory(mountPoint)) {
				throw new IllegalArgumentException("Fuse mount point does not exist: " + mountPoint);
			}
		}
	}

	private static void startup(Args args) throws IOException {
		Optional<WebDav> server = initWebDavServer(args);
		ArrayList<FuseMount> mounts = new ArrayList<>();
		ArrayList<mountInfo> mInfo = new ArrayList<>();
		for (String vaultName : args.getVaultNames()) {
			Path vaultPath = Paths.get(args.getVaultPath(vaultName));
			LOG.info("Unlocking vault \"{}\" located at {}", vaultName, vaultPath);
			String vaultPassword = args.getPasswordStrategy(vaultName).password();
			CryptoFileSystemProperties properties = CryptoFileSystemProperties.cryptoFileSystemProperties()
					.withPassphrase(vaultPassword).build();
			Path vaultRoot = CryptoFileSystemProvider.newFileSystem(vaultPath, properties).getPath("/");

			Path fuseMountPoint = args.getFuseMountPoint(vaultName);
			if (fuseMountPoint != null) {
				String mountFlags = args.fuseMountFlags(vaultName);
				FuseMount newMount = new FuseMount(vaultRoot, fuseMountPoint, mountFlags);
				if (newMount.mount()) {
					mounts.add(newMount);
					mInfo.add(new mountInfo(vaultPath.toString(), fuseMountPoint.toString()));
					server.ifPresent(serv -> serv.addServlet(vaultRoot, vaultName));
				}
			}
		}

		listenForUnMountEvents(mInfo);
	}

	private static Optional<WebDav> initWebDavServer(Args args) {
		Optional<WebDav> server = Optional.empty();
		if (args.hasValidWebDavConf()) {
			server = Optional.of(new WebDav(args.getBindAddr(), args.getPort()));
		}
		return server;
	}

	private static ArrayList<mountInfo> mountedList() {
		ArrayList<mountInfo> list = new ArrayList<>();
		try {
			 BufferedReader in  = new BufferedReader(new FileReader("/proc/self/mountinfo"));
			 while (true){
				 String s = in.readLine();
				 if (s==null){
					 break;
				 }else{
					 String[] entries = s.split(" ");
					 int dashPosition = 0;
					 for ( ; dashPosition < entries.length ; dashPosition++) {
						 if (strEq(entries[dashPosition], "-")){
							 break;
						 }
					}
					String tmp = entries[dashPosition+1];
					if (strEq(tmp.toLowerCase(), "fuse.cryptomator")) {
						list.add(new mountInfo(entries[dashPosition+2], entries[4]));
					}
				 }
			 }
		 } catch (java.io.FileNotFoundException e){
			LOG.error(e.getMessage());
		 } catch (IOException e){
			LOG.error(e.getMessage());
		 }
		 return list;
	}

	private static boolean hasActiveMount(ArrayList<mountInfo> localList) {
		ArrayList<mountInfo> globalList = mountedList();
		for (mountInfo m : localList){
			for (mountInfo xt : globalList){
				if (strEq(xt.mountPath, m.mountPath)){
					return true;
				}
			}
		}

		return false;
	}

	private static void listenForUnMountEvents(ArrayList<mountInfo> mounts) {
		while (true){
			if (hasActiveMount(mounts)){
				sleep(2);
			}else{
				LOG.info("All vaults are locked, exiting");
				break;
			}
		}
	}

	private static void sleep(int interval) {
		try {
			Object mainThreadBlockLock = new Object();
			synchronized (mainThreadBlockLock) {
				mainThreadBlockLock.wait(interval * 1000);
			}
		} catch (Exception e) {
			LOG.error("Main thread blocking failed.");
		}
	}

	private void printVersion() {
		System.out.println(this.getClass().getPackage().getImplementationVersion());
	}

	private static boolean controlArgs(String e,String[] cArgs ) {
		for (String m : cArgs){
			if (strEq(e, m)){
				return true;
			}
		}
		return false;
	}

	private static String[] update(String[] rawArgs) {
		int vaultCount = 0;
		String vaultName = "foo/bar/meaw";

		for (int i = 0 ; i < rawArgs.length ; i++) {
			if (strEqOne(rawArgs[i], "--vault", "-vault")){
				vaultCount++;
				if (i+1<rawArgs.length){
					vaultName =  rawArgs[i+1];
				}
			}
		}
		if (vaultCount != 1){
			return rawArgs;
		}

		String[] vaultNameComponents = vaultName.split("/");

		String prefix;

		if (vaultNameComponents.length > 0){
			prefix = vaultNameComponents[vaultNameComponents.length - 1];
		}else{
			prefix = "meaw";
		}

		String[] cArgs = new String[]{"-fusemount", "--fusemount", "-passwordfile",
					 "--passwordfile","-password", "--password", "-vault", "--vault"};

		for (int i = 0 ; i < rawArgs.length ; i++) {
			if ( i+1<rawArgs.length && strEqOne(rawArgs[i], "-mountFlags", "--mountFlags")) {
				rawArgs[i+1] = prefix + "=" + rawArgs[i+1].replace("=", FuseMount.encodeCharacterToString('='));
				i++;
			}else if (controlArgs(rawArgs[i],cArgs) && i+1<rawArgs.length){
				rawArgs[i+1] = prefix + "=" + rawArgs[i+1];
				i++;
			}
		}

		return rawArgs;
	}

	private static boolean strEq(String a, String b){
		return a.equals(b);
	}

	private static boolean strEqOne(String a, String ... b){
		for (String c: b){
			 if (a.equals(c)) {
				 return true;
			 }
		 }
		 return false;
	}
}

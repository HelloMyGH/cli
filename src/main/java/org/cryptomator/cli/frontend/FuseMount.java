package org.cryptomator.cli.frontend;

import java.nio.file.Path;

import org.cryptomator.frontend.fuse.mount.CommandFailedException;
import org.cryptomator.frontend.fuse.mount.EnvironmentVariables;
import org.cryptomator.frontend.fuse.mount.FuseMountFactory;
import org.cryptomator.frontend.fuse.mount.Mount;
import org.cryptomator.frontend.fuse.mount.Mounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FuseMount {
	private static final Logger LOG = LoggerFactory.getLogger(FuseMount.class);

	private Path vaultRoot;
	private Path vaultPath;
	private Path mountPoint;
	private Mount mnt;

	public FuseMount(Path vaultRoot, Path vaultPath, Path mountPoint) {
		this.vaultRoot = vaultRoot;
		this.mountPoint = mountPoint;
		this.vaultPath = vaultPath;
		this.mnt = null;
	}

	public boolean mount() {
		if (mnt != null) {
			LOG.info("Already mounted to {}", mountPoint);
			return false;
		}

		try {
			Mounter mounter = FuseMountFactory.getMounter();
			String[] mountFlags = mounter.defaultMountFlags();
			String[] newMountFlags = new String[mountFlags.length+2];
			for (int i = 0 ; i < mountFlags.length ; i++) {
				newMountFlags[i] = mountFlags[i];
			}
			newMountFlags[mountFlags.length] = "-osubtype=cryptomator";
			newMountFlags[mountFlags.length+1] ="-ofsname=cryptomator@"+vaultPath;
			EnvironmentVariables envVars = EnvironmentVariables.create().withFlags(newMountFlags)
					.withMountPoint(mountPoint).build();
			mnt = mounter.mount(vaultRoot, envVars);
			LOG.info("Mounted to {}", mountPoint);
		} catch (CommandFailedException e) {
			LOG.error("Can't mount: {}, error: {}", mountPoint, e.getMessage());
			return false;
		}
		return true;
	}

	public void unmount() {
		try {
			mnt.unmount();
			LOG.info("Unmounted {}", mountPoint);
		} catch (CommandFailedException e) {
			LOG.error("Can't unmount gracefully: {}. Force unmount.", e.getMessage());
			forceUnmount();
		}
	}

	private void forceUnmount() {
		try {
			mnt.unmountForced();
			LOG.info("Unmounted {}", mountPoint);
		} catch (CommandFailedException e) {
			LOG.error("Force unmount failed: {}", e.getMessage());
		}
	}
}


This project is a fork of https://github.com/cryptomator/cli and currently, it adds the following functionality:-

1. "--version" argument to print version information.
2. "--mountFlags" argument to add FUSE options.
3. "--foreground" argument to cause the program to run in the foreground.
    Not adding the feature causes the program to run daemonized(currently not supported)
4. A preffered way to lock a vault is by using "fusermount -u $MOUNT_POINT_PATH" and not sending SIGTERM signal.
    The benefit of this approach is fusermount's ability to notice if the mount point is in use or not and to refuse to
     unmount if it is. The SIGTERM approach just nukes the process even when mount points are in use and this may
     lead to data loss.
5. A saner API when unlocking a single vault and the example is:
    ```/usr/bin/java -jar cryptomator-cli.jar --vault $VAULT_PATH --fusemount $MOUNT_PATH --mountFlags rw,fsname=Cryptomator@$VAULT_PATH,subtype=Cryptomator```

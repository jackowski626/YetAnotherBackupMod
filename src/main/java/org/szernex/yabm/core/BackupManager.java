package org.szernex.yabm.core;

import org.apache.commons.io.FileUtils;
import org.lwjgl.Sys;
import org.szernex.yabm.handler.ConfigHandler;
import org.szernex.yabm.util.ChatHelper;
import org.szernex.yabm.util.FileHelper;
import org.szernex.yabm.util.LogHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BackupManager implements Runnable
{
	private BackupTask backupTask = new BackupTask();
	private FTPTask ftpTask = new FTPTask();
	private boolean running = false;


	public boolean isRunning()
	{
		return running;
	}

	private boolean isPersistentBackup()
	{
		if (!ConfigHandler.persistentEnabled || !ConfigHandler.compressBackups)
		{
			return false;
		}

		String archive_name = FileHelper.getArchiveFileName(ConfigHandler.backupPrefix, true);

		archive_name = archive_name.substring(0, archive_name.lastIndexOf("_"));

		try
		{
			File target_path = new File(ConfigHandler.persistentLocation).getCanonicalFile();

			if (!target_path.exists())
			{
				if (!target_path.mkdirs())
				{
					LogHelper.error("Could not create persistent backup directory %s", target_path.toString());
					return false;
				}

				return true;
			}

			File[] files = target_path.listFiles(new FileHelper.BackupFileFilter(archive_name));

			return (files.length == 0);
		}
		catch (IOException ex)
		{
			LogHelper.error("Error during persistent backup detection: %s", ex.getMessage());
			ex.printStackTrace();
			return false;
		}
	}

	private void consolidateBackups(String path, int max_backups)
	{
		if (max_backups < 0)
		{
			return;
		}

		String archive_name = FileHelper.getArchiveFileName(ConfigHandler.backupPrefix, false);

		try
		{
			File target_path = new File(path).getCanonicalFile();

			if (!target_path.exists())
			{
				return;
			}

			File[] files = target_path.listFiles(new FileHelper.BackupFileFilter(archive_name));

			File[] all_files = new File(path).listFiles();
			List<File> backup_files = new ArrayList<File>();

			for (int i = 0; i < all_files.length; i ++) {
				System.out.println(all_files[i].getName());
				if (all_files[i].getName().startsWith(ConfigHandler.backupPrefix)) {
					backup_files.add(all_files[i]);
				}
			}

			//File[] backup_files_array = (File[]) backup_files.toArray();

			File[] backup_files_array = backup_files.toArray(new File[backup_files.size()]);

			if (backup_files_array.length <= max_backups)
			{
				return;
			}

			Arrays.sort(backup_files_array);

			backup_files_array = Arrays.copyOfRange(backup_files_array, 0, backup_files_array.length - max_backups);
			ChatHelper.sendServerChatMsg(ChatHelper.getLocalizedMsg("yabm.backup.general.backup_consolidation", files.length, (files.length > 1 ? "s" : "")));

			for (File f : backup_files_array)
			{
				if (f.isFile()) {
					if (f.delete()) {
						LogHelper.info("Deleted old backup %s", f);
					} else {
						LogHelper.warn("Could not delete old backup %s", f);
					}
				} else if (f.isDirectory()) {
					FileUtils.deleteDirectory(f);
				}
			}
		}
		catch (IOException ex)
		{
			LogHelper.error("Error during consolidation: %s", ex.getMessage());
			ex.printStackTrace();
		}
	}

	private void startAndWaitForThread(Runnable task)
	{
		Thread thread = new Thread(task);

		thread.start();

		try
		{
			thread.join();
		}
		catch (InterruptedException ex)
		{
			LogHelper.error("Thread got interrupted: %s", ex.getMessage());
			ex.printStackTrace();
		}
	}

	@Override
	public void run()
	{
		running = true;

		boolean do_consolidation = true;

		backupTask.init((isPersistentBackup() ? ConfigHandler.persistentLocation : ConfigHandler.backupLocation),
		                ConfigHandler.backupPrefix,
		                ConfigHandler.backupList,
		                ConfigHandler.compressionLevel,
						ConfigHandler.compressBackups
		);

		startAndWaitForThread(backupTask);

		if (ConfigHandler.ftpEnabled)
		{
			if (backupTask.getLastBackupFile() != null)
			{
				ftpTask.init(backupTask.getLastBackupFile(),
				             ConfigHandler.ftpServer,
				             ConfigHandler.ftpPort,
				             ConfigHandler.ftpUsername,
				             ConfigHandler.ftpPassword,
				             ConfigHandler.ftpLocation
				);
			}

			startAndWaitForThread(ftpTask);
			do_consolidation = ftpTask.didLastTaskSucceed();
		}

		if (do_consolidation)
		{
			consolidateBackups(ConfigHandler.backupLocation, ConfigHandler.maxBackupCount);
			consolidateBackups(ConfigHandler.persistentLocation, ConfigHandler.maxPersistentCount);
		}

		running = false;
	}

	public void startBackup()
	{
		new Thread(this).start();
	}
}

package net.querz.mcaselector.io;

import net.querz.mcaselector.ui.ProgressTask;
import net.querz.mcaselector.util.Debug;
import net.querz.mcaselector.util.Helper;
import net.querz.mcaselector.util.Point2i;
import net.querz.mcaselector.util.Timer;
import net.querz.mcaselector.util.Translation;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ChunkImporter {

	private ChunkImporter() {}

	public static void importChunks(File importDir, ProgressTask progressChannel, boolean overwrite, Point2i offset) {
		try {
			File[] importFiles = importDir.listFiles((dir, name) -> name.matches(Helper.MCA_FILE_PATTERN));
			if (importFiles == null || importFiles.length == 0) {
				progressChannel.done(Translation.DIALOG_PROGRESS_NO_FILES.toString());
				return;
			}

			MCAFilePipe.clearQueues();

			progressChannel.setMax(importFiles.length * (offset.getX() % 32 != 0 ? 2 : 1) * (offset.getY() % 32 != 0 ? 2 : 1));
			progressChannel.infoProperty().setValue(Translation.DIALOG_PROGRESS_COLLECTING_DATA.toString());

			Map<Point2i, Set<Point2i>> targetMapping = new HashMap<>();


			// find target files
			for (File file : importFiles) {
				Point2i source = Helper.parseMCAFileName(file);
				if (source == null) {
					Debug.dumpf("could not parse region from mca file name: %s", file.getName());
					continue;
				}

				try {
					Set<Point2i> targets = getTargetRegions(source, offset);
					mapSourceRegionsByTargetRegion(source, targets, targetMapping);
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}

			progressChannel.updateProgress(importFiles[0].getName(), 0);

			for (Map.Entry<Point2i, Set<Point2i>> entry : targetMapping.entrySet()) {
				File targetFile = Helper.createMCAFilePath(entry.getKey());
				MCAFilePipe.addJob(new MCAChunkImporterLoadJob(targetFile, importDir, entry.getKey(), entry.getValue(), offset, progressChannel, overwrite));
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	public static class MCAChunkImporterLoadJob extends LoadDataJob {

		private Point2i target;
		private Set<Point2i> sources;
		private File sourceDir;
		private Point2i offset;
		private ProgressTask progressChannel;
		private boolean overwrite;

		MCAChunkImporterLoadJob(File targetFile, File sourceDir, Point2i target, Set<Point2i> sources, Point2i offset, ProgressTask progressChannel, boolean overwrite) {
			super(targetFile);
			this.target = target;
			this.sources = sources;
			this.sourceDir = sourceDir;
			this.offset = offset;
			this.progressChannel = progressChannel;
			this.overwrite = overwrite;
		}

		@Override
		public void execute() {

			// special case for non existing destination file and no offset
			if (offset.getX() == 0 && offset.getY() == 0 && !getFile().exists()) {
				//if the entire mca file doesn't exist, just copy it over
				File source = new File(sourceDir, getFile().getName());
				try {

					Files.copy(source.toPath(), getFile().toPath());
				} catch (IOException ex) {
					Debug.errorf("failed to copy file %s to %s: %s", source, getFile(), ex.getMessage());
				}
				progressChannel.incrementProgress(getFile().getName(), sources.size());
				return;
			}

			// regular case

			Map<Point2i, byte[]> sourceDataMapping = new HashMap<>();

			for (Point2i sourceRegion : sources) {
				File source = new File(sourceDir, Helper.createMCAFileName(sourceRegion));

				byte[] sourceData = load(source);

				if (sourceData == null) {
					Debug.errorf("error loading source mca file %s", source.getName());
					progressChannel.incrementProgress(getFile().getName());
					continue;
				}

				sourceDataMapping.put(sourceRegion, sourceData);
			}

			if (sourceDataMapping.isEmpty()) {
				Debug.errorf("could not load any source mca files to merge into %s with offset %s", getFile().getName(), offset);
				// don't increment progress here, if there were errors loading it has already been incremented
				return;
			}

			byte[] destData;

			if (getFile().exists()) {
				destData = load();
				if (destData == null) {
					Debug.errorf("error loading destination mca file %s", getFile().getName());
					progressChannel.incrementProgress(getFile().getName(), sourceDataMapping.size());
					return;
				}
			} else {
				destData = null;
			}

			MCAFilePipe.executeProcessData(new MCAChunkImporterProcessJob(getFile(), sourceDir, target, sourceDataMapping, destData, offset, progressChannel, overwrite));
		}
	}

	public static class MCAChunkImporterProcessJob extends ProcessDataJob {

		private File sourceDir;
		private Point2i target;
		private Map<Point2i, byte[]> sourceDataMapping;
		private Point2i offset;
		private ProgressTask progressChannel;
		private boolean overwrite;

		MCAChunkImporterProcessJob(File targetFile, File sourceDir, Point2i target, Map<Point2i, byte[]> sourceDataMapping, byte[] destData, Point2i offset, ProgressTask progressChannel, boolean overwrite) {
			super(targetFile, destData);
			this.sourceDir = sourceDir;
			this.target = target;
			this.sourceDataMapping = sourceDataMapping;
			this.offset = offset;
			this.progressChannel = progressChannel;
			this.overwrite = overwrite;
		}

		@Override
		public void execute() {
			Timer t = new Timer();
			try {
				MCAFile destination;
				// no destination file: create new MCAFile
				if (getData() == null) {
					 destination = new MCAFile(getFile());
				} else {
					destination = MCAFile.readAll(getFile(), new ByteArrayPointer(getData()));
				}

				if (destination == null) {
					progressChannel.incrementProgress(getFile().getName(), sourceDataMapping.size());
					Debug.errorf("failed to load target MCAFile %s", getFile().getName());
					return;
				}

				for (Map.Entry<Point2i, byte[]> sourceData : sourceDataMapping.entrySet()) {
					MCAFile source = MCAFile.readAll(new File(sourceDir, Helper.createMCAFileName(sourceData.getKey())), new ByteArrayPointer(sourceData.getValue()));

					Debug.dumpf("merging chunk from  region %s into %s", sourceData.getKey(), target);

					source.mergeChunksInto(destination, getRelativeOffset(sourceData.getKey(), target, offset), overwrite);
				}

				MCAFilePipe.executeSaveData(new MCAChunkImporterSaveJob(getFile(), destination, sourceDataMapping.size(), progressChannel));

			} catch (Exception ex) {
				Debug.errorf("error merging chunks into %s with offset %s: %s", getFile(), offset, ex.getMessage());
				progressChannel.incrementProgress(getFile().getName());
			}

			Debug.dumpf("took %s to merge chunks into %s with offset %s", t, getFile(), offset);
		}
	}

	public static class MCAChunkImporterSaveJob extends SaveDataJob<MCAFile> {

		private int sourceCount;
		private ProgressTask progressChannel;

		MCAChunkImporterSaveJob(File file, MCAFile data, int sourceCount, ProgressTask progressChannel) {
			super(file, data);
			this.sourceCount = sourceCount;
			this.progressChannel = progressChannel;
		}

		@Override
		public void execute() {
			Timer t = new Timer();
			try {
				File tmpFile = File.createTempFile(getFile().getName(), null, null);
				try (RandomAccessFile raf = new RandomAccessFile(tmpFile, "rw")) {
					getData().saveAll(raf);
				}
				Files.move(tmpFile.toPath(), getFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception ex) {
				Debug.error(ex);
			}
			progressChannel.incrementProgress(getFile().getName(), sourceCount);
			Debug.dumpf("took %s to save data to %s", t, getFile().getName());
		}
	}

	public static void mapSourceRegionsByTargetRegion(Point2i source, Set<Point2i> targets, Map<Point2i, Set<Point2i>> map) {
		for (Point2i target : targets) {
			map.computeIfAbsent(target, key -> new HashSet<>(4));
			map.get(target).add(source);
		}
	}


	// source is a region coordinate, offset is a chunk coordinate
	public static Set<Point2i> getTargetRegions(Point2i source, Point2i offset) {
		Set<Point2i> result = new HashSet<>(4);
		Point2i sourceChunk = Helper.regionToChunk(source).add(offset);
		result.add(Helper.chunkToRegion(sourceChunk));
		result.add(Helper.chunkToRegion(sourceChunk.add(31, 0)));
		result.add(Helper.chunkToRegion(sourceChunk.add(0, 31)));
		result.add(Helper.chunkToRegion(sourceChunk.add(31, 31)));
		return result;
	}

	public static Point2i getRelativeOffset(Point2i source, Point2i target, Point2i offset) {
		return Helper.regionToChunk(source).add(offset).sub(Helper.regionToChunk(target));
	}
}

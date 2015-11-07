package net.pms.util;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import net.pms.dlna.DLNAMediaInfo;
import net.pms.dlna.DLNAMediaSubtitle;
import net.pms.formats.FormatFactory;
import net.pms.formats.v2.SubtitleType;
import static net.pms.util.Constants.*;
import org.apache.commons.io.FilenameUtils;
import static org.apache.commons.lang3.StringUtils.*;
import org.codehaus.plexus.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sun.jna.Platform;
import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

public class FileUtil {
	private static final Logger LOGGER = LoggerFactory.getLogger(FileUtil.class);
	private static Map<File, File[]> cache;

	// Signal an invalid parameter in getFileLocation() without raising an exception or returning null
	private static final String DEFAULT_BASENAME = "NO_DEFAULT_BASENAME_SUPPLIED.conf";

	// This class is not instantiable
	private FileUtil() { }

	/**
	 * A helper class used by {@link #getFileLocation(String, String, String)}
	 * which provides access to a file's absolute path and that of its directory.
	 *
	 * @since 1.90.0
	 */
	public static final class FileLocation {
		private String directoryPath;
		private String filePath;

		FileLocation(File directory, File file) {
			this.directoryPath = FilenameUtils.normalize(directory.getAbsolutePath());
			this.filePath = FilenameUtils.normalize(file.getAbsolutePath());
		}

		public String getDirectoryPath() {
			return directoryPath;
		}

		public String getFilePath() {
			return filePath;
		}
	}

	/**
	 * Returns a {@link FileLocation} object which provides access to the directory
	 * and file paths of the specified file as normalised, absolute paths.
	 *
	 * This determines the directory and file path of a file according to the rules
	 * outlined here: http://www.ps3mediaserver.org/forum/viewtopic.php?f=6&amp;t=3507&amp;p=49895#p49895
	 *
	 * @param customPath an optional user-defined path for the resource
	 * @param defaultDirectory a default directory path used if no custom path is provided
	 * @param defaultBasename a default filename used if a) no custom path is provided
	 *                        or b) the custom path is a directory
	 * @return a {@link FileLocation} object providing access to the file's directory and file paths
	 * @since 1.90.0
	 */
	// this is called from a static initialiser, where errors aren't clearly reported,
	// so do everything possible to return a valid reponse, even if the parameters
	// aren't sane
	static public FileLocation getFileLocation(
		String customPath,
		String defaultDirectory,
		String defaultBasename
	) {
		File customFile = null;
		File directory = null;
		File file = null;

		if (isBlank(defaultBasename)) {
			// shouldn't get here
			defaultBasename = DEFAULT_BASENAME;
		}

		if (defaultDirectory == null) {
			defaultDirectory = ""; // current directory
		}

		if (customPath != null) {
			customFile = new File(customPath).getAbsoluteFile();
		}

		if (customFile != null) {
			if (customFile.exists()) {
				if (customFile.isDirectory()) {
					directory = customFile;
					file = new File(customFile, defaultBasename).getAbsoluteFile();
				} else {
					directory = customFile.getParentFile();
					file = customFile;
				}
			} else {
				File parentDirectoryFile = customFile.getParentFile();
				if (parentDirectoryFile != null && parentDirectoryFile.exists()) {
					// parent directory exists: the file can be created
					directory = parentDirectoryFile;
					file = customFile;
				}
			}
		}

		if (directory == null || file == null) {
			directory = new File(defaultDirectory).getAbsoluteFile();
			file = new File(directory, defaultBasename).getAbsoluteFile();
		}

		return new FileLocation(directory, file);
	}

	public static File isFileExists(String f, String ext) {
		return isFileExists(new File(f), ext);
	}

	public static boolean isUrl(String filename) {
		// We're intentionally avoiding stricter URI() methods, which can throw
		// URISyntaxException for psuedo-urls (e.g. librtmp-style urls containing spaces)
		return filename != null && filename.matches("\\S+://.*");
	}

	public static String getProtocol(String filename) {
		// Intentionally avoids URI.getScheme(), see above
		if (isUrl(filename)) {
			return filename.split("://")[0].toLowerCase();
		}
		return null;
	}

	public static String urlJoin(String base, String filename) {
		if (isUrl(filename)) {
			return filename;
		}
		try {
			return new URL(new URL(base), filename).toString();
		} catch (MalformedURLException e) {
			return filename;
		}
	}

	public static String getUrlExtension(String u) {
		// Omit the query string, if any
		return getExtension(substringBefore(u, "?"));
	}

	public static String getExtension(String f) {
		int point = f.lastIndexOf('.');

		if (point == -1) {
			return null;
		}

		return f.substring(point + 1);
	}

	public static String getFileNameWithoutExtension(String f) {
		int point = f.lastIndexOf('.');

		if (point == -1) {
			point = f.length();
		}

		return f.substring(0, point);
	}

	/**
	 * Returns the filename after being "prettified", which involves
	 * attempting to strip away certain things like information about the
	 * quality, resolution, codecs, release groups, fansubbers, etc.,
	 * replacing periods with spaces, and various other things to produce a
	 * more "pretty" and standardized filename.
	 *
	 * @param f The filename
	 * @param file The file to possibly be used by the InfoDb
	 *
	 * @return The prettified filename
	 */
	public static String getFileNameWithRewriting(String f, File file) {
		String fileNameWithoutExtension;
		String formattedName;
		String formattedNameTemp;
		String searchFormattedName;
		boolean loopedOnce = false;

		// These are false unless we recognize that we could use some info on the video from IMDB
		boolean isEpisodeToLookup = false;
		boolean isMovieToLookup   = false;

		// Remove file extension
		fileNameWithoutExtension = getFileNameWithoutExtension(f);
		formattedName = fileNameWithoutExtension;
		searchFormattedName = "";

		String commonFileEnds = "[\\s\\.]AC3.*|[\\s\\.]REPACK.*|[\\s\\.]480p.*|[\\s\\.]720p.*|[\\s\\.]m-720p.*|[\\s\\.]900p.*|[\\s\\.]1080p.*|[\\s\\.]2160p.*|[\\s\\.]WEB-DL.*|[\\s\\.]HDTV.*|[\\s\\.]DSR.*|[\\s\\.]PDTV.*|[\\s\\.]WS.*|[\\s\\.]HQ.*|[\\s\\.]DVDRip.*|[\\s\\.]TVRiP.*|[\\s\\.]BDRip.*|[\\s\\.]BRRip.*|[\\s\\.]WEBRip.*|[\\s\\.]BluRay.*|[\\s\\.]Blu-ray.*|[\\s\\.]SUBBED.*|[\\s\\.]x264.*|[\\s\\.]Dual[\\s\\.]Audio.*|[\\s\\.]HSBS.*|[\\s\\.]H-SBS.*|[\\s\\.]RERiP.*|[\\s\\.]DIRFIX.*|[\\s\\.]READNFO.*|[\\s\\.]60FPS.*";
		String commonFileEndsMatch = ".*[\\s\\.]AC3.*|.*[\\s\\.]REPACK.*|.*[\\s\\.]480p.*|.*[\\s\\.]720p.*|.*[\\s\\.]m-720p.*|.*[\\s\\.]900p.*|.*[\\s\\.]1080p.*|.*[\\s\\.]2160p.*|.*[\\s\\.]WEB-DL.*|.*[\\s\\.]HDTV.*|.*[\\s\\.]DSR.*|.*[\\s\\.]PDTV.*|.*[\\s\\.]WS.*|.*[\\s\\.]HQ.*|.*[\\s\\.]DVDRip.*|.*[\\s\\.]TVRiP.*|.*[\\s\\.]BDRip.*|.*[\\s\\.]BRRip.*|.*[\\s\\.]WEBRip.*|.*[\\s\\.]BluRay.*|.*[\\s\\.]Blu-ray.*|.*[\\s\\.]SUBBED.*|.*[\\s\\.]x264.*|.*[\\s\\.]Dual[\\s\\.]Audio.*|.*[\\s\\.]HSBS.*|.*[\\s\\.]H-SBS.*|.*[\\s\\.]RERiP.*|.*[\\s\\.]DIRFIX.*|.*[\\s\\.]READNFO.*|.*[\\s\\.]60FPS.*";
		String commonFileEndsCaseSensitive = "[\\s\\.]PROPER[\\s\\.].*|[\\s\\.]iNTERNAL[\\s\\.].*|[\\s\\.]LIMITED[\\s\\.].*|[\\s\\.]LiMiTED[\\s\\.].*|[\\s\\.]FESTiVAL[\\s\\.].*|[\\s\\.]NORDIC[\\s\\.].*|[\\s\\.]REAL[\\s\\.].*|[\\s\\.]SUBBED[\\s\\.].*|[\\s\\.]RETAIL[\\s\\.].*|[\\s\\.]EXTENDED[\\s\\.].*";

		String commonFileMiddle = "(?i)(?!\\()(Special[\\s\\.]Edition|Unrated|Final[\\s\\.]Cut|Remastered|Extended[\\s\\.]Cut|IMAX[\\s\\.]Edition|Uncensored|Directors[\\s\\.]Cut)(?!\\))";

		if (formattedName.matches(".*[sS]0\\d[eE]\\d\\d[eE]\\d\\d.*")) {
			// This matches scene and most p2p TV episodes within the first 9 seasons that are double or triple episodes

			// Rename the season/episode numbers. For example, "S01E01" changes to " - 101"
			// Then strip the end of the episode if it does not have the episode name in the title
			formattedName = formattedName.replaceAll("(?i)[\\s\\.]S0(\\d)E(\\d)(\\d)E(\\d)(\\d)(" + commonFileEnds + ")", " - $1$2$3-$1$4$5");
			formattedName = formattedName.replaceAll("[\\s\\.]S0(\\d)E(\\d)(\\d)E(\\d)(\\d)(" + commonFileEndsCaseSensitive + ")", " - $1$2$3-$1$4$5");

			// If it matches this then it didn't match the previous one, which means there is probably an episode title in the filename
			formattedNameTemp = formattedName.replaceAll("(?i)[\\s\\.]S0(\\d)E(\\d)(\\d)E(\\d)(\\d)[\\s\\.]", " - $1$2$3-$1$4$5 - ");

			if (PMS.getConfiguration().isUseInfoFromIMDB() && formattedName.equals(formattedNameTemp)) {
				isEpisodeToLookup = true;
			}

			// Wrap edition information in brackets
			formattedName = formattedNameTemp.replaceAll(" - " + commonFileMiddle, " ($1)");
			formattedName = formattedName.replaceAll(commonFileMiddle, "($1)");

			// Remove stuff at the end of the filename like release group, quality, source, etc.
			formattedName = formattedName.replaceAll(commonFileEndsCaseSensitive, "");
			formattedName = formattedName.replaceAll("(?i)" + commonFileEnds, "");

			// Replace periods with spaces
			formattedName = formattedName.replaceAll("\\.", " ");

			// Capitalize the first letter of each word if the string contains no capital letters
			if (formattedName.equals(formattedName.toLowerCase())) {
				formattedNameTemp = "";
				for (String part : formattedName.split(" - ")) {
					if (loopedOnce) {
						formattedNameTemp += " - " + convertLowerCaseStringToTitleCase(part);
					} else {
						formattedNameTemp += convertLowerCaseStringToTitleCase(part);
					}
					loopedOnce = true;
				}
				formattedName = formattedNameTemp;
			}
		} else if (formattedName.matches(".*[sS][1-9]\\d[eE]\\d\\d[eE]\\d\\d.*")) {
			// This matches scene and most p2p TV episodes after their first 9 seasons that are double episodes

			// Rename the season/episode numbers. For example, "S11E01" changes to " - 1101"
			formattedName = formattedName.replaceAll("(?i)[\\s\\.]S([1-9]\\d)E(\\d)(\\d)E(\\d)(\\d)(" + commonFileEnds + ")", " - $1$2$3-$1$4$5");
			formattedName = formattedName.replaceAll("[\\s\\.]S([1-9]\\d)E(\\d)(\\d)E(\\d)(\\d)(" + commonFileEndsCaseSensitive + ")", " - $1$2$3-$1$4$5");

			// If it matches this then it didn't match the previous one, which means there is probably an episode title in the filename
			formattedNameTemp = formattedName.replaceAll("(?i)[\\s\\.]S([1-9]\\d)E(\\d)(\\d)E(\\d)(\\d)[\\s\\.]", " - $1$2$3-$1$4$5 - ");

			if (PMS.getConfiguration().isUseInfoFromIMDB() && formattedName.equals(formattedNameTemp)) {
				isEpisodeToLookup = true;
			}

			// Wrap edition information in brackets
			formattedName = formattedNameTemp.replaceAll(" - " + commonFileMiddle, " ($1)");
			formattedName = formattedName.replaceAll(commonFileMiddle, "($1)");

			// Remove stuff at the end of the filename like release group, quality, source, etc.
			formattedName = formattedName.replaceAll(commonFileEndsCaseSensitive, "");
			formattedName = formattedName.replaceAll("(?i)" + commonFileEnds, "");

			// Replace periods with spaces
			formattedName = formattedName.replaceAll("\\.", " ");

			// Capitalize the first letter of each word if the string contains no capital letters
			if (formattedName.equals(formattedName.toLowerCase())) {
				formattedNameTemp = "";
				for (String part : formattedName.split(" - ")) {
					if (loopedOnce) {
						formattedNameTemp += " - " + convertLowerCaseStringToTitleCase(part);
					} else {
						formattedNameTemp += convertLowerCaseStringToTitleCase(part);
					}
					loopedOnce = true;
				}
				formattedName = formattedNameTemp;
			}
		} else if (formattedName.matches(".*[sS]0\\d[eE]\\d\\d.*")) {
			// This matches scene and most p2p TV episodes within the first 9 seasons

			// Rename the season/episode numbers. For example, "S01E01" changes to " - 101"
			// Then strip the end of the episode if it does not have the episode name in the title
			formattedName = formattedName.replaceAll("(?i)[\\s\\.]S0(\\d)E(\\d)(\\d)(" + commonFileEnds + ")", " - $1$2$3");
			formattedName = formattedName.replaceAll("[\\s\\.]S0(\\d)E(\\d)(\\d)(" + commonFileEndsCaseSensitive + ")", " - $1$2$3");

			// If it matches this then it didn't match the previous one, which means there is probably an episode title in the filename
			formattedNameTemp = formattedName.replaceAll("(?i)[\\s\\.]S0(\\d)E(\\d)(\\d)[\\s\\.]", " - $1$2$3 - ");

			if (PMS.getConfiguration().isUseInfoFromIMDB() && formattedName.equals(formattedNameTemp)) {
				isEpisodeToLookup = true;
			}

			// Wrap edition information in brackets
			formattedName = formattedNameTemp.replaceAll(" - " + commonFileMiddle, " ($1)");
			formattedName = formattedName.replaceAll(commonFileMiddle, "($1)");

			// Remove stuff at the end of the filename like release group, quality, source, etc.
			formattedName = formattedName.replaceAll(commonFileEndsCaseSensitive, "");
			formattedName = formattedName.replaceAll("(?i)" + commonFileEnds, "");

			// Replace periods with spaces
			formattedName = formattedName.replaceAll("\\.", " ");

			// Capitalize the first letter of each word if the string contains no capital letters
			if (formattedName.equals(formattedName.toLowerCase())) {
				formattedNameTemp = "";
				for (String part : formattedName.split(" - ")) {
					if (loopedOnce) {
						formattedNameTemp += " - " + convertLowerCaseStringToTitleCase(part);
					} else {
						formattedNameTemp += convertLowerCaseStringToTitleCase(part);
					}
					loopedOnce = true;
				}
				formattedName = formattedNameTemp;
			}
		} else if (formattedName.matches(".*[sS][1-9]\\d[eE]\\d\\d.*")) {
			// This matches scene and most p2p TV episodes after their first 9 seasons

			// Rename the season/episode numbers. For example, "S11E01" changes to " - 1101"
			formattedName = formattedName.replaceAll("(?i)[\\s\\.]S([1-9]\\d)E(\\d)(\\d)(" + commonFileEnds + ")", " - $1$2$3");
			formattedName = formattedName.replaceAll("[\\s\\.]S([1-9]\\d)E(\\d)(\\d)(" + commonFileEndsCaseSensitive + ")", " - $1$2$3");

			// If it matches this then it didn't match the previous one, which means there is probably an episode title in the filename
			formattedNameTemp = formattedName.replaceAll("(?i)[\\s\\.]S([1-9]\\d)E(\\d)(\\d)[\\s\\.]", " - $1$2$3 - ");

			if (PMS.getConfiguration().isUseInfoFromIMDB() && formattedName.equals(formattedNameTemp)) {
				isEpisodeToLookup = true;
			}

			// Wrap edition information in brackets
			formattedName = formattedNameTemp.replaceAll(" - " + commonFileMiddle, " ($1)");
			formattedName = formattedName.replaceAll(commonFileMiddle, "($1)");

			// Remove stuff at the end of the filename like release group, quality, source, etc.
			formattedName = formattedName.replaceAll(commonFileEndsCaseSensitive, "");
			formattedName = formattedName.replaceAll("(?i)" + commonFileEnds, "");

			// Replace periods with spaces
			formattedName = formattedName.replaceAll("\\.", " ");

			// Capitalize the first letter of each word if the string contains no capital letters
			if (formattedName.equals(formattedName.toLowerCase())) {
				formattedNameTemp = "";
				for (String part : formattedName.split(" - ")) {
					if (loopedOnce) {
						formattedNameTemp += " - " + convertLowerCaseStringToTitleCase(part);
					} else {
						formattedNameTemp += convertLowerCaseStringToTitleCase(part);
					}
					loopedOnce = true;
				}
				formattedName = formattedNameTemp;
			}
		} else if (formattedName.matches(".*[\\s\\.](19|20)\\d\\d[\\s\\.][0-1]\\d[\\s\\.][0-3]\\d[\\s\\.].*")) {
			// This matches scene and most p2p TV episodes that release several times per week

			// Rename the date. For example, "2013.03.18" changes to " - 2013/03/18"
			formattedName = formattedName.replaceAll("(?i)[\\s\\.](19|20)(\\d\\d)[\\s\\.]([0-1]\\d)[\\s\\.]([0-3]\\d)(" + commonFileEnds + ")", " - $1$2/$3/$4");
			formattedName = formattedName.replaceAll("[\\s\\.](19|20)(\\d\\d)[\\s\\.]([0-1]\\d)[\\s\\.]([0-3]\\d)(" + commonFileEndsCaseSensitive + ")", " - $1$2/$3/$4");

			// If it matches this then it didn't match the previous one, which means there is probably an episode title in the filename
			formattedNameTemp = formattedName.replaceAll("(?i)[\\s\\.](19|20)(\\d\\d)[\\s\\.]([0-1]\\d)[\\s\\.]([0-3]\\d)[\\s\\.]", " - $1$2/$3/$4 - ");

			if (PMS.getConfiguration().isUseInfoFromIMDB() && formattedName.equals(formattedNameTemp)) {
				isEpisodeToLookup = true;
			}

			// Wrap edition information in brackets
			formattedName = formattedNameTemp.replaceAll(" - " + commonFileMiddle, " ($1)");
			formattedName = formattedName.replaceAll(commonFileMiddle, "($1)");

			// Remove stuff at the end of the filename like release group, quality, source, etc.
			formattedName = formattedName.replaceAll(commonFileEndsCaseSensitive, "");
			formattedName = formattedName.replaceAll("(?i)" + commonFileEnds, "");

			// Replace periods with spaces
			formattedName = formattedName.replaceAll("\\.", " ");

			// Capitalize the first letter of each word if the string contains no capital letters
			if (formattedName.equals(formattedName.toLowerCase())) {
				formattedNameTemp = "";
				for (String part : formattedName.split(" - ")) {
					if (loopedOnce) {
						formattedNameTemp += " - " + convertLowerCaseStringToTitleCase(part);
					} else {
						formattedNameTemp += convertLowerCaseStringToTitleCase(part);
					}
					loopedOnce = true;
				}
				formattedName = formattedNameTemp;
			}
		} else if (formattedName.matches(".*[\\s\\.](19|20)\\d\\d[\\s\\.].*")) {
			// This matches scene and most p2p movies

			// Rename the year. For example, "2013" changes to " (2013)"
			formattedName = formattedName.replaceAll("[\\s\\.](19|20)(\\d\\d)", " ($1$2)");

			// Remove stuff at the end of the filename like release group, quality, source, etc.
			formattedName = formattedName.replaceAll(commonFileEndsCaseSensitive, "");
			formattedName = formattedName.replaceAll("(?i)" + commonFileEnds, "");

			formattedName = formattedName.replaceAll(commonFileMiddle, "($1)");

			// Replace periods with spaces
			formattedName = formattedName.replaceAll("\\.", " ");

			// Capitalize the first letter of each word if the string contains no capital letters
			if (formattedName.equals(formattedName.toLowerCase())) {
				formattedName = convertLowerCaseStringToTitleCase(formattedName);
			}
		} else if (formattedName.matches(".*\\[(19|20)\\d\\d\\].*")) {
			// This matches rarer types of movies

			// Rename the year. For example, "2013" changes to " (2013)"
			formattedName = formattedName.replaceAll("(?i)\\[(19|20)(\\d\\d)\\].*", " ($1$2)");

			// Replace periods with spaces
			formattedName = formattedName.replaceAll("\\.", " ");

			// Capitalize the first letter of each word if the string contains no capital letters
			if (formattedName.equals(formattedName.toLowerCase())) {
				formattedName = convertLowerCaseStringToTitleCase(formattedName);
			}
		} else if (formattedName.matches(".*\\((19|20)\\d\\d\\).*")) {
			// This matches rarer types of movies

			// Remove stuff at the end of the filename like release group, quality, source, etc.
			formattedName = formattedName.replaceAll(commonFileEndsCaseSensitive, "");
			formattedName = formattedName.replaceAll("(?i)" + commonFileEnds, "");

			// Capitalize the first letter of each word if the string contains no capital letters
			if (formattedName.equals(formattedName.toLowerCase())) {
				formattedName = convertLowerCaseStringToTitleCase(formattedName);
			}
		} else if (formattedName.matches(".*\\((19|20)\\d\\d\\).*")) {
			// This matches rarer types of movies

			// Remove stuff at the end of the filename like release group, quality, source, etc.
			formattedName = formattedName.replaceAll(commonFileEndsCaseSensitive, "");
			formattedName = formattedName.replaceAll("(?i)" + commonFileEnds, "");

			// Capitalize the first letter of each word if the string contains no capital letters
			if (formattedName.equals(formattedName.toLowerCase())) {
				formattedName = convertLowerCaseStringToTitleCase(formattedName);
			}
		} else if (formattedName.matches(commonFileEndsMatch)) {
			// This is probably a movie that doesn't specify a year
			isMovieToLookup = true;

			// Remove stuff at the end of the filename like release group, quality, source, etc.
			formattedName = formattedName.replaceAll(commonFileEndsCaseSensitive, "");
			formattedName = formattedName.replaceAll("(?i)" + commonFileEnds, "");
			formattedName = formattedName.replaceAll(commonFileMiddle, "($1)");

			// Replace periods with spaces
			formattedName = formattedName.replaceAll("\\.", " ");

			// Capitalize the first letter of each word if the string contains no capital letters
			if (formattedName.equals(formattedName.toLowerCase())) {
				formattedName = convertLowerCaseStringToTitleCase(formattedName);
			}
		} else if (formattedName.matches(".*\\[[0-9a-zA-Z]{8}\\]$")) {
			// This matches anime with a hash at the end of the name

			// Remove underscores
			formattedName = formattedName.replaceAll("_", " ");

			// Remove stuff at the end of the filename like hash, quality, source, etc.
			formattedName = formattedName.replaceAll("(?i)\\s\\(1280x720.*|\\s\\(1920x1080.*|\\s\\(720x400.*|\\[720p.*|\\[1080p.*|\\[480p.*|\\s\\(BD.*|\\s\\[Blu-Ray.*|\\s\\[DVD.*|\\.DVD.*|\\[[0-9a-zA-Z]{8}\\]$|\\[h264.*|R1DVD.*|\\[BD.*", "");

			// Remove group name from the beginning of the filename
			if (!"".equals(formattedName)) {
				if (formattedName.substring(0, 1).matches("\\[")) {
					int closingBracketIndex = formattedName.indexOf(']');
					if (closingBracketIndex != -1) {
						formattedName = formattedName.substring(closingBracketIndex + 1);
					}

					if (formattedName.substring(0, 1).matches("\\s")) {
						formattedName = formattedName.substring(1);
					}
				}
			} else {
				formattedName = fileNameWithoutExtension;
			}

			if (PMS.getConfiguration().isUseInfoFromIMDB() && formattedName.substring(formattedName.length() - 3).matches("[\\s\\._]\\d\\d")) {
				isEpisodeToLookup = true;
				searchFormattedName = formattedName.substring(0, formattedName.length() - 2) + "S01E" + formattedName.substring(formattedName.length() - 2);
			}

			// Capitalize the first letter of each word if the string contains no capital letters
			if (formattedName.equals(formattedName.toLowerCase())) {
				formattedName = convertLowerCaseStringToTitleCase(formattedName);
			}
		} else if (formattedName.matches(".*\\[BD\\].*|.*\\[720p\\].*|.*\\[1080p\\].*|.*\\[480p\\].*|.*\\[Blu-Ray.*|.*\\[h264.*")) {
			// This matches anime without a hash in the name

			// Remove underscores
			formattedName = formattedName.replaceAll("_", " ");

			// Remove stuff at the end of the filename like hash, quality, source, etc.
			formattedName = formattedName.replaceAll("(?i)\\[BD\\].*|\\[720p.*|\\[1080p.*|\\[480p.*|\\[Blu-Ray.*\\[h264.*", "");

			// Remove group name from the beginning of the filename
			if (!"".equals(formattedName)) {
				if (formattedName.substring(0, 1).matches("\\[")) {
					int closingBracketIndex = formattedName.indexOf(']');
					if (closingBracketIndex != -1) {
						formattedName = formattedName.substring(closingBracketIndex + 1);
					}

					if (formattedName.substring(0, 1).matches("\\s")) {
						formattedName = formattedName.substring(1);
					}
				}
			} else {
				formattedName = fileNameWithoutExtension;
			}

			if (PMS.getConfiguration().isUseInfoFromIMDB() && formattedName.substring(formattedName.length() - 3).matches("[\\s\\._]\\d\\d")) {
				isEpisodeToLookup = true;
				searchFormattedName = formattedName.substring(0, formattedName.length() - 2) + "S01E" + formattedName.substring(formattedName.length() - 2);
			}

			// Capitalize the first letter of each word if the string contains no capital letters
			if (formattedName.equals(formattedName.toLowerCase())) {
				formattedName = convertLowerCaseStringToTitleCase(formattedName);
			}
		}

		// Add episode name (if not there)
		if (file != null && (isEpisodeToLookup || isMovieToLookup)) {
			InfoDb.InfoDbData info = PMS.get().infoDb().get(file);
			if (info == null) {
				PMS.get().infoDbAdd(file, searchFormattedName);
			} else if (isEpisodeToLookup && StringUtils.isNotEmpty(info.ep_name)) {
				formattedName += " - " + info.ep_name;
			} else if (isMovieToLookup && StringUtils.isNotEmpty(info.year)) {
				formattedName += " (" + info.year + ")";
			}
		}

		return formattedName;
	}

	/**
	 * Converts a lower case string to title case.
	 *
	 * It is not very smart right now so it can be expanded to be more reliable.
	 *
	 * @param value the string to convert
	 *
	 * @return the converted string
	 */
	public static String convertLowerCaseStringToTitleCase(String value) {
		String convertedValue = "";
		boolean loopedOnce = false;

		for (String word : value.split(" ")) {
			if (loopedOnce) {
				switch (word) {
					case "a":
					case "an":
					case "and":
					case "in":
					case "it":
					case "for":
					case "of":
					case "on":
					case "the":
					case "to":
					case "vs":
						convertedValue += ' ' + word;
						break;
					default:
						convertedValue += ' ' + word.substring(0, 1).toUpperCase() + word.substring(1);
				}
			} else {
				// Always capitalize the first letter of the string
				convertedValue += word.substring(0, 1).toUpperCase() + word.substring(1);
			}
			loopedOnce = true;
		}

		return convertedValue;
	}

	public static File getFileNameWithNewExtension(File parent, File file, String ext) {
		return isFileExists(new File(parent, file.getName()), ext);
	}

	/**
	 * @deprecated Use {@link #getFileNameWithNewExtension(File, File, String)}.
	 */
	@Deprecated
	public static File getFileNameWitNewExtension(File parent, File f, String ext) {
		return getFileNameWithNewExtension(parent, f, ext);
	}

	public static File getFileNameWithAddedExtension(File parent, File f, String ext) {
		File ff = new File(parent, f.getName() + ext);

		if (ff.exists()) {
			return ff;
		}

		return null;
	}

	/**
	 * @deprecated Use {@link #getFileNameWithAddedExtension(File, File, String)}.
	 */
	@Deprecated
	public static File getFileNameWitAddedExtension(File parent, File file, String ext) {
		return getFileNameWithAddedExtension(parent, file, ext);
	}

	public static File isFileExists(File f, String ext) {
		int point = f.getName().lastIndexOf('.');

		if (point == -1) {
			point = f.getName().length();
		}

		File lowerCasedFile = new File(f.getParentFile(), f.getName().substring(0, point) + "." + ext.toLowerCase());
		if (lowerCasedFile.exists()) {
			return lowerCasedFile;
		}

		File upperCasedFile = new File(f.getParentFile(), f.getName().substring(0, point) + "." + ext.toUpperCase());
		if (upperCasedFile.exists()) {
			return upperCasedFile;
		}

		return null;
	}

	/**
	 * @deprecated Use {@link #isSubtitlesExists(File file, DLNAMediaInfo media)} instead.
	 */
	@Deprecated
	public static boolean doesSubtitlesExists(File file, DLNAMediaInfo media) {
		return isSubtitlesExists(file, media);
	}

	public static boolean isSubtitlesExists(File file, DLNAMediaInfo media) {
		return isSubtitlesExists(file, media, true);
	}

	/**
	 * @deprecated Use {@link #isSubtitlesExists(File file, DLNAMediaInfo media, boolean usecache)} instead.
	 */
	@Deprecated
	public static boolean doesSubtitlesExists(File file, DLNAMediaInfo media, boolean usecache) {
		return isSubtitlesExists(file, media, usecache);
	}

	public static boolean isSubtitlesExists(File file, DLNAMediaInfo media, boolean usecache) {
		boolean found = false;
		if (file.exists()) {
			found = browseFolderForSubtitles(file.getParentFile(), file, media, usecache);
		}
		String alternate = PMS.getConfiguration().getAlternateSubtitlesFolder();

		if (isNotBlank(alternate)) { // https://code.google.com/p/ps3mediaserver/issues/detail?id=737#c5
			File subFolder = new File(alternate);

			if (!subFolder.isAbsolute()) {
				subFolder = new File(file.getParent() + "/" + alternate);
				try {
					subFolder = subFolder.getCanonicalFile();
				} catch (IOException e) {
					LOGGER.debug("Caught exception", e);
				}
			}

			if (subFolder.exists()) {
				found = found || browseFolderForSubtitles(subFolder, file, media, usecache);
			}
		}

		return found;
	}

	private synchronized static boolean browseFolderForSubtitles(File subFolder, File file, DLNAMediaInfo media, boolean usecache) {
		boolean found = false;

		if (!usecache) {
			cache = null;
		}

		if (cache == null) {
			cache = new HashMap<>();
		}

		final Set<String> supported = SubtitleType.getSupportedFileExtensions();

		File[] allSubs = cache.get(subFolder);
		if (allSubs == null) {
			allSubs = subFolder.listFiles(
				new FilenameFilter() {
					@Override
					public boolean accept(File dir, String name) {
						String ext = FilenameUtils.getExtension(name).toLowerCase();
						if ("sub".equals(ext)) {
							// Avoid microdvd/vobsub confusion by ignoring sub+idx pairs here since
							// they'll come in unambiguously as vobsub via the idx file anyway
							return isFileExists(new File(dir, name), "idx") == null;
						}
						return supported.contains(ext);
					}
				}
			);

			if (allSubs != null) {
				cache.put(subFolder, allSubs);
			}
		}

		String fileName = getFileNameWithoutExtension(file.getName()).toLowerCase();
		if (allSubs != null) {
			for (File f : allSubs) {
				if (f.isFile() && !f.isHidden()) {
					String fName = f.getName().toLowerCase();
					for (String ext : supported) {
						if (fName.length() > ext.length() && fName.startsWith(fileName) && endsWithIgnoreCase(fName, "." + ext)) {
							int a = fileName.length();
							int b = fName.length() - ext.length() - 1;
							String code = "";

							if (a <= b) { // handling case with several dots: <video>..<extension>
								code = fName.substring(a, b);
							}

							if (code.startsWith(".")) {
								code = code.substring(1);
							}

							boolean exists = false;
							if (media != null) {
								for (DLNAMediaSubtitle sub : media.getSubtitleTracksList()) {
									if (f.equals(sub.getExternalFile())) {
										exists = true;
									} else if (equalsIgnoreCase(ext, "idx") && sub.getType() == SubtitleType.MICRODVD) { // sub+idx => VOBSUB
										sub.setType(SubtitleType.VOBSUB);
										exists = true;
									} else if (equalsIgnoreCase(ext, "sub") && sub.getType() == SubtitleType.VOBSUB) { // VOBSUB
										try {
											sub.setExternalFile(f);
										} catch (FileNotFoundException ex) {
											LOGGER.warn("Exception during external subtitles scan.", ex);
										}

										exists = true;
									}
								}
							}

							if (!exists) {
								DLNAMediaSubtitle sub = new DLNAMediaSubtitle();
								sub.setId(100 + (media == null ? 0 : media.getSubtitleTracksList().size())); // fake id, not used
								if (code.length() == 0 || !Iso639.codeIsValid(code)) {
									sub.setLang(DLNAMediaSubtitle.UND);
									sub.setType(SubtitleType.valueOfFileExtension(ext));
									if (code.length() > 0) {
										sub.setSubtitlesTrackTitleFromMetadata(code);
										if (sub.getSubtitlesTrackTitleFromMetadata().contains("-")) {
											String flavorLang = sub.getSubtitlesTrackTitleFromMetadata().substring(0, sub.getSubtitlesTrackTitleFromMetadata().indexOf('-'));
											String flavorTitle = sub.getSubtitlesTrackTitleFromMetadata().substring(sub.getSubtitlesTrackTitleFromMetadata().indexOf('-') + 1);
											if (Iso639.codeIsValid(flavorLang)) {
												sub.setLang(flavorLang);
												sub.setSubtitlesTrackTitleFromMetadata(flavorTitle);
											}
										}
									}
								} else {
									sub.setLang(code);
									sub.setType(SubtitleType.valueOfFileExtension(ext));
								}

								try {
									sub.setExternalFile(f);
								} catch (FileNotFoundException ex) {
									LOGGER.warn("Exception during external subtitles scan.", ex);
								}

								found = true;
								if (media != null) {
									media.getSubtitleTracksList().add(sub);
								}
							}
						}
					}
				}
			}
		}

		return found;
	}

	/**
	 * Detects charset/encoding for given file. Not 100% accurate for
	 * non-Unicode files.
	 *
	 * @param file the file for which to detect charset/encoding
	 * @return The match object form the detection process or <code>null</code> if no match was found
	 * @throws IOException
	 */
	public static CharsetMatch getFileCharsetMatch(File file) throws IOException {
		CharsetMatch result = null;
		try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
			CharsetDetector detector = new CharsetDetector();
			detector.enableInputFilter(true);
			detector.setText(in);
			// Results are sorted on descending confidence, so we're only after the first one.
			result = detector.detectAll()[0];
		}

		if (result != null) {
			LOGGER.debug("Detected encoding for {} is {}.", file.getAbsolutePath(), result.getName());
		} else {
			LOGGER.debug("No encoding detected for {}.", file.getAbsolutePath());
		}

		return result;
	}

	/**
	 * Detects charset/encoding for given file. Not 100% accurate for
	 * non-Unicode files.
	 *
	 * @param file the file for which to detect charset/encoding
	 * @return The detected <code>Charset</code> or <code>null</code> if not detected
	 * @throws IOException
	 */
	public static Charset getFileCharset(File file) throws IOException {
		CharsetMatch match = getFileCharsetMatch(file);
		if (match != null) {
			try {
				if (Charset.isSupported(match.getName())) {
					LOGGER.debug("Detected charset \"{}\" in file {}", match.getName(), file.getAbsolutePath());
					return Charset.forName(match.getName());
				} else {
					LOGGER.debug(
						"Detected charset \"{}\" in file {}, but cannot use it because it's not supported by the Java Virual Machine",
						match.getName(),
						file.getAbsolutePath()
					);
					return null;
				}
			} catch (IllegalCharsetNameException e) {
				LOGGER.debug("Illegal charset deteceted \"{}\" in file {}", match.getName(), file.getAbsolutePath());
			}
		}
		LOGGER.debug("Found no matching charset for file {}", file.getAbsolutePath());
		return null;
	}

	/**
	 * Detects charset/encoding for given file. Not 100% accurate for
	 * non-Unicode files.
	 *
	 * @param file the file for which to detect charset/encoding
	 * @return The name of the detected charset or <code>null</code> if not detected
	 * @throws IOException
	 */
	public static String getFileCharsetName(File file) throws IOException {
		CharsetMatch match = getFileCharsetMatch(file);
		if (match != null) {
			LOGGER.debug("Detected charset \"{}\" in file {}", match.getName(), file.getAbsolutePath());
			return match.getName().toUpperCase(PMS.getLocale());
		} else {
			LOGGER.debug("Found no matching charset for file {}", file.getAbsolutePath());
			return null;
		}
	}

	/**
	 * Tests if file is UTF-8 encoded with or without BOM.
	 *
	 * @param file File to test
	 * @return True if file is UTF-8 encoded with or without BOM, false otherwise.
	 * @throws IOException
	 */
	public static boolean isFileUTF8(File file) throws IOException {
		return isCharsetUTF8(getFileCharset(file));
	}

	/**
	 * Tests if charset is UTF-8.
	 *
	 * @param charset <code>Charset</code> to test
	 * @return True if charset is UTF-8, false otherwise.
	 */
	public static boolean isCharsetUTF8(Charset charset) {
		return charset != null && charset.equals(StandardCharsets.UTF_8);
	}

	/**
	 * Tests if charset is UTF-8.
	 *
	 * @param charset charset name to test
	 * @return True if charset is UTF-8, false otherwise.
	 */
	public static boolean isCharsetUTF8(String charsetName) {
		return equalsIgnoreCase(charsetName, CHARSET_UTF_8);
	}

	/**
	 * Tests if file is UTF-16 encoded.
	 *
	 * @param file File to test
	 * @return True if file is UTF-16 encoded, false otherwise.
	 * @throws IOException
	 */
	public static boolean isFileUTF16(File file) throws IOException {
		return isCharsetUTF16(getFileCharset(file));
	}

	/**
	 * Tests if charset is UTF-16.
	 *
	 * @param charset <code>Charset</code> to test
	 * @return True if charset is UTF-16, false otherwise.
	 */
	public static boolean isCharsetUTF16(Charset charset) {
		return charset != null && (charset.equals(StandardCharsets.UTF_16) || charset.equals(StandardCharsets.UTF_16BE) || charset.equals(StandardCharsets.UTF_16LE));
	}

	/**
	 * Tests if charset is UTF-16.
	 *
	 * @param charset charset name to test
	 * @return True if charset is UTF-16, false otherwise.
	 */
	public static boolean isCharsetUTF16(String charsetName) {
		return (equalsIgnoreCase(charsetName, CHARSET_UTF_16LE) || equalsIgnoreCase(charsetName, CHARSET_UTF_16BE));
	}

	/**
	 * Tests if charset is UTF-32.
	 *
	 * @param charsetName charset name to test
	 * @return True if charset is UTF-32, false otherwise.
	 */
	public static boolean isCharsetUTF32(String charsetName) {
		return (equalsIgnoreCase(charsetName, CHARSET_UTF_32LE) || equalsIgnoreCase(charsetName, CHARSET_UTF_32BE));
	}

	/**
	 * Converts UTF-16 inputFile to UTF-8 outputFile. Does not overwrite existing outputFile file.
	 *
	 * @param inputFile UTF-16 file
	 * @param outputFile UTF-8 file after conversion
	 * @throws IOException
	 */
	public static void convertFileFromUtf16ToUtf8(File inputFile, File outputFile) throws IOException {
		Charset charset;
		if (inputFile == null || !inputFile.canRead()) {
			throw new FileNotFoundException("Can't read inputFile.");
		}

		try {
			charset = getFileCharset(inputFile);
		} catch (IOException ex) {
			LOGGER.debug("Exception during charset detection.", ex);
			throw new IllegalArgumentException("Can't confirm inputFile is UTF-16.");
		}

		if (isCharsetUTF16(charset)) {
			if (!outputFile.exists()) {
				BufferedReader reader = null;
				/*
				 * This is a strange hack, and I'm not sure if it's needed. I
				 * did it this way to conform to the tests, which dictates that
				 * UTF-16LE should produce UTF-8 without BOM while UTF-16BE
				 * should produce UTF-8 with BOM.
				 *
				 * For some reason creating a FileInputStream with UTF_16 produces
				 * an UTF-8 outputfile without BOM, while using UTF_16LE or
				 * UTF_16BE produces an UTF-8 outputfile with BOM.
				 * @author Nadahar
				 */
				if (charset.equals(StandardCharsets.UTF_16LE)) {
					reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputFile), StandardCharsets.UTF_16));
				} else {
					reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputFile), charset));
				}

				BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8));
				int c;

				while ((c = reader.read()) != -1) {
					writer.write(c);
				}

				writer.close();
				reader.close();
			}
		} else {
			throw new IllegalArgumentException("File is not UTF-16");
		}
	}

	/**
	 * Return a file or folder's permissions.<br><br>
	 *
	 * This should <b>NOT</b> be used for checking e.g. read permissions before
	 * trying to open a file, because you can't assume that the same is true
	 * when you actually open the file. Other threads or processes could have
	 * locked the file (or changed it's permissions) in the meanwhile. Instead,
	 * use e.g <code>FileNotFoundException</code> like this:
	 * <pre><code>
	 * } catch (FileNotFoundException e) {
	 * 	LOGGER.debug("Can't read xxx {}", e.getMessage());
	 * }
	 * </code></pre>
	 * <code>e.getMessage()</code> will contain both the full path to the file
	 * the reason it couldn't be read (e.g. no permission).
	 *
	 * @param file The file or folder to check permissions for
	 * @return A <code>FilePermissions</code> object holding the permissions
	 * @throws FileNotFoundException
	 * @see {@link #getFilePermissions(String)}
	 */
	public static FilePermissions getFilePermissions(File file) throws FileNotFoundException {
		return new FilePermissions(file);
	}

	/**
	 * Like {@link #getFilePermissions(File)} but returns <code>null</code>
	 * instead of throwing <code>FileNotFoundException</code> if the file or
	 * folder isn't found.
	 */
	public static FilePermissions getFilePermissionsNoThrow(File file) {
		try {
			return new FilePermissions(file);
		} catch (FileNotFoundException | IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Return a file or folder's permissions.<br><br>
	 *
	 * This should <b>NOT</b> be used for checking e.g. read permissions before
	 * trying to open a file, because you can't assume that the same is true
	 * when you actually open the file. Other threads or processes could have
	 * locked the file (or changed it's permissions) in the meanwhile. Instead,
	 * use e.g <code>FileNotFoundException</code> like this:
	 * <pre><code>
	 * } catch (FileNotFoundException e) {
	 * 	LOGGER.debug("Can't read xxx {}", e.getMessage());
	 * }
	 * </code></pre>
	 * <code>e.getMessage()</code> will contain both the full path to the file
	 * the reason it couldn't be read (e.g. no permission).
	 *
	 * @param path The file or folder name to check permissions for
	 * @return A <code>FilePermissions</code> object holding the permissions
	 * @throws FileNotFoundException
	 * @see {@link #getFilePermissions(File)}
	 */
	public static FilePermissions getFilePermissions(String path) throws FileNotFoundException {
		if (path != null) {
			return new FilePermissions(new File(path));
		} else {
			File file = null;
			return new FilePermissions(file);
		}
	}

	/**
	 * Like {@link #getFilePermissions(String)} but returns <code>null</code>
	 * instead of throwing <code>FileNotFoundException</code> if the file or
	 * folder isn't found.
	 */
	public static FilePermissions getFilePermissionsNoThrow(String path) {
		if (path != null) {
			try {
				return new FilePermissions(new File(path));
			} catch (FileNotFoundException | IllegalArgumentException e) {
				return null;
			}
		} else {
			return null;
		}
	}

	public static boolean isFileRelevant(File f, PmsConfiguration configuration) {
		String fileName = f.getName().toLowerCase();
		if (
			(
				configuration.isArchiveBrowsing() &&
				(
					fileName.endsWith(".zip") ||
					fileName.endsWith(".cbz") ||
					fileName.endsWith(".rar") ||
					fileName.endsWith(".cbr")
				)
			) ||
			fileName.endsWith(".iso") ||
			fileName.endsWith(".img") ||
			fileName.endsWith(".m3u") ||
			fileName.endsWith(".m3u8") ||
			fileName.endsWith(".pls") ||
			fileName.endsWith(".cue")
		) {
			return true;
		}

		return false;
	}

	public static boolean isFolderRelevant(File f, PmsConfiguration configuration) {
		return isFolderRelevant(f, configuration, Collections.<String>emptySet());
	}

	public static boolean isFolderRelevant(File f, PmsConfiguration configuration, Set<String> ignoreFiles) {
		if (f.isDirectory() && configuration.isHideEmptyFolders()) {
			File[] children = f.listFiles();

			/**
			 * listFiles() returns null if "this abstract pathname does not denote a directory, or if an I/O error occurs".
			 * in this case (since we've already confirmed that it's a directory), this seems to mean the directory is non-readable
			 * http://www.ps3mediaserver.org/forum/viewtopic.php?f=6&t=15135
			 * http://stackoverflow.com/questions/3228147/retrieving-the-underlying-error-when-file-listfiles-return-null
			 */
			if (children == null) {
				LOGGER.warn("Can't list files in non-readable directory: {}", f.getAbsolutePath());
			} else {
				for (File child : children) {
					if (ignoreFiles.contains(child.getAbsolutePath())) {
						continue;
					}

					if (child.isFile()) {
						if (FormatFactory.getAssociatedFormat(child.getName()) != null || isFileRelevant(child, configuration)) {
							return true;
						}
					} else {
						if (isFolderRelevant(child, configuration, ignoreFiles)) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	public static String renameForSorting(String filename) {
		if (PMS.getConfiguration().isPrettifyFilenames()) {
			// This chunk makes anime sort properly
			int squareBracketIndex;
			if (filename.substring(0, 1).matches("\\[")) {
				filename = filename.replaceAll("_", " ");
				squareBracketIndex = filename.indexOf(']');
				if (squareBracketIndex != -1) {
					filename = filename.substring(squareBracketIndex + 1);
					if (filename.substring(0, 1).matches("\\s")) {
						filename = filename.substring(1);
					}
				}
			}

			// Replace periods with spaces
			filename = filename.replaceAll("\\.", " ");
		}

		if (PMS.getConfiguration().isIgnoreTheWordThe()) {
			// Remove "The" from the beginning of files
			filename = filename.replaceAll("^(?i)The[ .]", "");
		}

		return filename;
	}

	public static BufferedReader bufferedReaderWithCorrectCharset(File file) throws IOException {
		BufferedReader reader;
		Charset fileCharset = getFileCharset(file);
		if (fileCharset != null) {
			reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), fileCharset));
		} else {
			reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
			LOGGER.warn("Could not detect character encoding for file \"{}\". It will probably be interpreted wrong", file.getAbsolutePath());
		}
		return reader;
	}

	/**
	 * Checks for valid file name syntax. Path is not allowed.
	 *
	 * @param fileName the file name to be verified
	 * @return whether or not the file name is valid
	 */
	public static boolean isValidFileName(String fileName) {
		if (Platform.isWindows()) {
			if (fileName.matches("^[^\"*:<>?/\\\\]+$")) {
				return true;
			}
		} else if (Platform.isMac()) {
			if (fileName.matches("^[^:/]+$")) {
				return true;
			}
		} else {
			// Assuming POSIX
			if (fileName.matches("^[A-Za-z0-9._][A-Za-z0-9._-]*$")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Appends a path separator of the same type last in the string if
	 * it's not already there.
	 * @param path the path to be modified
	 * @return the corrected path
	 */
	public static String appendPathSeparator(String path) {
		if (!path.endsWith("\\") && !path.endsWith("/")) {
			if (path.contains("\\")) {
				path += "\\";
			} else {
				path += "/";
			}
		}
		return path;
	}

	private static Boolean admin = null;
	private static Object isAdminLock = new Object();

	/**
	 * Determines whether or not the program has admin/root permissions.
	 */
	public static boolean isAdmin() {
		synchronized(isAdminLock) {
			if (admin != null) {
				return admin;
			}
			if (Platform.isWindows()) {
				Float ver = null;
				try {
					ver = Float.valueOf(System.getProperty("os.version"));
				} catch (NullPointerException | NumberFormatException e) {
					LOGGER.error(
						"Could not determine Windows version from {}. Administrator privileges is undetermined: {}",
						System.getProperty("os.version"), e.getMessage()
					);
					admin = false;
					return false;
				}
				if (ver >= 5.1) {
					try {
						String command = "reg query \"HKU\\S-1-5-19\"";
						Process p = Runtime.getRuntime().exec(command);
						p.waitFor();
						int exitValue = p.exitValue();

						if (0 == exitValue) {
							admin = true;
							return true;
						}
						admin = false;
						return false;
					} catch (IOException | InterruptedException e) {
						LOGGER.error("An error prevented UMS from checking Windows permissions: {}", e.getMessage());
					}
				} else {
					admin = true;
					return true;
				}
			} else if (Platform.isLinux() || Platform.isMac()) {
				try {
					final String command = "id -Gn";
					LOGGER.trace("isAdmin: Executing \"{}\"", command);
					Process p = Runtime.getRuntime().exec(command);
					InputStream is = p.getInputStream();
					InputStreamReader isr = new InputStreamReader(is, StandardCharsets.US_ASCII);
					BufferedReader br = new BufferedReader(isr);
					p.waitFor();
					int exitValue = p.exitValue();
					String exitLine = br.readLine();
					if (exitValue != 0 || exitLine == null || exitLine.isEmpty()) {
						LOGGER.error("Could not determine root privileges, \"{}\" ended with exit code: {}", command, exitValue);
						admin = false;
						return false;
					}
					LOGGER.trace("isAdmin: \"{}\" returned {}", command, exitLine);
					if
						((Platform.isLinux() && exitLine.matches(".*\\broot\\b.*")) ||
						(Platform.isMac() && exitLine.matches(".*\\badmin\\b.*")))
					{
						LOGGER.trace("isAdmin: UMS has {} privileges", Platform.isLinux() ? "root" : "admin");
						admin = true;
						return true;
					}
					LOGGER.trace("isAdmin: UMS does not have {} privileges", Platform.isLinux() ? "root" : "admin");
					admin = false;
					return false;
				} catch (IOException | InterruptedException e) {
					LOGGER.error("An error prevented UMS from checking {} permissions: {}", Platform.isMac() ? "OS X" : "Linux" ,e.getMessage());
				}
			}
			admin = false;
			return false;
		}
	}
}

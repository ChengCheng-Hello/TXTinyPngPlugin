import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.StreamUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.tinify.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.*;
import java.lang.Exception;
import java.util.*;

/**
 * TinyPng压缩
 * <p>
 * 1. 需要在主工程目录下创建 ting.properties。
 * 2. TinyPngApiKey: 需要在 https://tinypng.com/ 注册获取ApiKey, 目前一个ApiKey每月可免费压缩图片500次。
 * 4. UsedCompressionCount: 当前已使用压缩次数。
 * 5. 如果压缩率小于 30% 相当于压缩过，不再压缩。
 * 6. 压缩和不需要压缩的分开提交git
 * <p>
 * <p>
 * Created by Cheng on 17/3/1.
 */
public class TXTinyPngAction extends AnAction {

    private static final String PROFILE = "tiny.properties";
    private static final String KEY_API_KEY = "TinyPngApiKey";
    private static final String kEY_USED_COUNT = "UsedCompressionCount";
    private static final float MIN_COMPRESSION_RATIO = 0.3f;
    private static final String SETTING_GRADLE = "/settings.gradle";
    private static final String GIT_DIR = "/.git";

    private String mApiKey;
    private int mEffectiveCount;
    private HashMap<String, String> mProperties;
    private StatusBar mStatusBar;

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (null == project) {
            return;
        }

        mStatusBar = WindowManager.getInstance().getStatusBar(DataKeys.PROJECT.getData(e.getDataContext()));

        mEffectiveCount = 0;

        // 获取项目路径 ../tianxiao-android
        final String basePath = project.getBasePath();

        showStatusBarMessage("开始任务...");

        asyncTask(new Runnable() {
            @Override
            public void run() {
                // 获取TinyPng api key
                ApplicationManager.getApplication().runReadAction(new Runnable() {
                    @Override
                    public void run() {
                        InputStream is = null;
                        try {
                            File proFile = new File(basePath, PROFILE);
                            if (!proFile.exists()) {
                                proFile.createNewFile();

                            }

                            mProperties = new HashMap<>();

                            is = new FileInputStream(proFile);
                            Properties properties = new Properties();
                            properties.load(is);

                            for (String next : properties.stringPropertyNames()) {
                                mProperties.put(next, properties.getProperty(next));
                            }

                            mApiKey = properties.getProperty(KEY_API_KEY);
                        } catch (IOException e1) {
                            showAsyncMessage("io error");
                            e1.printStackTrace();
                        } finally {
                            StreamUtil.closeStream(is);
                        }
                    }
                });

                if (StringUtil.isEmpty(mApiKey)) {
                    showAsyncMessage("no TinyPng api key");
                    return;
                }

                showAsyncMessage("验证TinyPng ApiKey...");

                Tinify.setKey("38w2RidTDXLpEiF7NE_YQkx8aOA4Aoek");
                Tinify.validate();

                showAsyncMessage("本月使用压缩次数 " + Tinify.compressionCount());

                final HashMap<Repository, List<String>> imagePathMap = new HashMap<>();

                // 主工程 图片
                Repository mainRepository = getRepository(basePath);
                List<String> mainImagePath = getImagePath(mainRepository);
                if (mainImagePath != null && mainImagePath.size() > 0) {
                    imagePathMap.put(mainRepository, mainImagePath);
                }

                // module 图片
                List<String> modulePathList = new ArrayList<>();
                String settingGradle = getFileOutputString(basePath + SETTING_GRADLE);
                if (!StringUtil.isEmpty(settingGradle)) {
                    String[] split = settingGradle.split("new File");
                    for (String item : split) {
                        if (!item.contains("../")) {
                            continue;
                        }

                        int start = item.indexOf("/");
                        int end = item.lastIndexOf("/");

                        modulePathList.add(item.substring(start, end));
                    }
                }
                for (String path : modulePathList) {
                    Repository moduleRepository = getRepository(basePath.substring(0, basePath.lastIndexOf("/")) + path);
                    List<String> moduleImagePath = getImagePath(moduleRepository);
                    if (moduleImagePath != null && moduleImagePath.size() > 0) {
                        imagePathMap.put(moduleRepository, moduleImagePath);
                    }
                }

                if (imagePathMap.size() == 0) {
                    showAsyncMessage("没有图片 新增or修改");
                    return;
                }

                // 压缩图片，执行git commit
                Set<Map.Entry<Repository, List<String>>> entries = imagePathMap.entrySet();
                for (Map.Entry<Repository, List<String>> item : entries) {
                    Repository repository = item.getKey();
                    List<String> imagePathList = item.getValue();

                    int count = compressImageAndCommit(repository, imagePathList);
                    mEffectiveCount += count;
                }

                uiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 本月使用压缩次数，free 500/month
                        final int compressionCount = Tinify.compressionCount();

                        // 修改lastModifyTime
                        ApplicationManager.getApplication().runWriteAction(new Runnable() {
                            @Override
                            public void run() {
                                InputStream is = null;
                                OutputStream os = null;
                                try {

                                    File proFile = new File(basePath, PROFILE);
                                    if (!proFile.exists()) {
                                        proFile.createNewFile();

                                    }

                                    is = new FileInputStream(proFile);
                                    os = new FileOutputStream(proFile);
                                    Properties properties = new Properties();
                                    properties.load(is);

                                    if (mProperties != null) {
                                        for (String next : mProperties.keySet()) {
                                            properties.setProperty(next, mProperties.get(next));
                                        }
                                    }

                                    properties.setProperty(kEY_USED_COUNT, String.valueOf(compressionCount));
                                    properties.store(os, null);

                                    // 修改文件后，不会在IDE中实时刷新，需要你调用VirtualFile的refresh方法。
                                    VirtualFile vf = LocalFileSystem.getInstance().findFileByIoFile(proFile);
                                    vf.refresh(true, false);
                                } catch (IOException e1) {
                                    showAsyncMessage(e1.getLocalizedMessage());
                                    e1.printStackTrace();
                                } finally {
                                    StreamUtil.closeStream(is);
                                    StreamUtil.closeStream(os);
                                }
                            }
                        });

                        showMessage("本月使用压缩次数 " + compressionCount + ", 本次有效压缩图片数 " + mEffectiveCount);
                    }
                });
            }
        });
    }

    private void asyncTask(Runnable runnable) {
        ApplicationManager.getApplication().executeOnPooledThread(runnable);
    }

    private void uiThread(Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(runnable);
    }

    private void showAsyncMessage(final String message) {
        uiThread(new Runnable() {
            @Override
            public void run() {
                showStatusBarMessage(message);
            }
        });
    }

    private void showMessage(final String message) {
        showStatusBarMessage(message);
    }

    private void showStatusBarMessage(String message) {
        mStatusBar.setInfo(message);
    }

    /*
     * 读取指定文件的输出
     */
    private static String getFileOutputString(String path) {
        try {
            BufferedReader bufferedReader = new BufferedReader(new FileReader(path), 8192);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                sb.append("\n").append(line);
            }
            bufferedReader.close();
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 获取git仓库
     *
     * @param basePath git根目录
     * @return
     */
    private Repository getRepository(String basePath) {
        FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
        repositoryBuilder.setMustExist(true);
        repositoryBuilder.setGitDir(new File(basePath + GIT_DIR));
        try {
            return repositoryBuilder.build();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * 获取git仓库下，新增or改变的图片路径
     *
     * @param repository git仓库
     * @return
     */
    private List<String> getImagePath(Repository repository) {
        try {
            Git git = Git.wrap(repository);

            git.reset().setMode(ResetCommand.ResetType.MIXED).call();

            Status call = git.status().call();
            Set<String> uncommittedChanges = call.getUncommittedChanges();
            Set<String> untracked = call.getUntracked();
            Set<String> removed = call.getRemoved();

            List<String> pathList = new ArrayList<>();
            for (String path : uncommittedChanges) {
                pathList.add(path);
            }
            for (String path : untracked) {
                if (!pathList.contains(path)) {
                    pathList.add(path);
                }
            }
            for (String path : removed) {
                if (pathList.contains(path)) {
                    pathList.remove(path);
                }
            }

            List<String> imagePathList = new ArrayList<>();
            for (String filePath : pathList) {
                if (!filePath.endsWith(".9.png") && (filePath.endsWith(".png") || filePath.endsWith(".jpg"))) {
                    showAsyncMessage(filePath);
                    imagePathList.add(filePath);
                }
            }
            return imagePathList;
        } catch (GitAPIException e1) {
            e1.printStackTrace();
        }

        return null;
    }

    /**
     * 压缩图片，提交git
     *
     * @param repository    git仓库
     * @param imagePathList 图片
     * @return
     */
    private int compressImageAndCommit(Repository repository, List<String> imagePathList) {
        Git git = Git.wrap(repository);

        List<String> noCompressList = new ArrayList<>();
        List<String> compressList = new ArrayList<>();

        for (String filePath : imagePathList) {
            File file = new File(repository.getWorkTree(), filePath);
            if (!file.exists()) {
                System.out.println("file not exists" + file);
                continue;
            }

            try {
                long oriLength = file.length();

                Source source = Tinify.fromFile(file.getAbsolutePath());
                Result result = source.result();

                long compressedLength = result.size();

                System.out.println("result.mediaType() = " + result.mediaType());
                System.out.println("result.size() = " + compressedLength);


                float compress = (oriLength - compressedLength) / (float) oriLength;

                showAsyncMessage("compress " + file.getName() + ", compress " + compress);

                // 压缩率小于 30% 相当于压缩过，不再压缩
                if (compress < MIN_COMPRESSION_RATIO) {
                    noCompressList.add(filePath);
                    continue;
                } else {
                    compressList.add(filePath);
                }

                // 直接替换文件
                result.toFile(file.getAbsolutePath());
            } catch (AccountException e) {
                System.out.println("The error message is: " + e.getMessage());
                showAsyncMessage(e.getMessage());
                // Verify your API key and account limit.
            } catch (ClientException e) {
                System.out.println("The error message is: " + e.getMessage());
                showAsyncMessage(e.getMessage());
                // Check your source image and request options.
            } catch (ServerException e) {
                System.out.println("The error message is: " + e.getMessage());
                showAsyncMessage(e.getMessage());
                // Temporary issue with the Tinify API.
            } catch (ConnectionException e) {
                System.out.println("The error message is: " + e.getMessage());
                showAsyncMessage(e.getMessage());
                // A network connection error occurred.
            } catch (Exception e) {
                System.out.println("The error message is: " + e.getMessage());
                showAsyncMessage(e.getMessage());
                // Something else went wrong, unrelated to the Tinify API.
            }
        }

        try {
            if (noCompressList.size() > 0) {
                for (String filePath : noCompressList) {
                    git.add().addFilepattern(filePath).call();
                }

                git.commit()
                        .setMessage(String.format("不需要压缩图片%1$d张", noCompressList.size()))
                        .setAllowEmpty(false).call();
            }

            if (compressList.size() > 0) {
                for (String filePath : compressList) {
                    git.add().addFilepattern(filePath).call();
                }

                git.commit().
                        setMessage(String.format("压缩图片%1$d张", compressList.size()))
                        .setAllowEmpty(false).call();
            }
        } catch (GitAPIException e1) {
            e1.printStackTrace();
        }

        return compressList.size();
    }
}

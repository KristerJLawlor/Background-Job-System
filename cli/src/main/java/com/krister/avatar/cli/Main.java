package com.krister.avatar.cli;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.krister.avatar.core.DiscordImageResizer;

public class Main {
    //Build with .\gradlew.bat build      
    //Run with .\gradlew.bat :cli:run 

    private static final int THREAD_COUNT = 4;  //Number of parallel threads we will use
    
    public static void main(String[] args) {

        //Attempt to get image URL from user and process it
        Scanner scanner = new Scanner(System.in);
        
        //Set the project root to the parent of current directory, so we can save the avatars
        //In the project root instead of the cli folder (current directory)
        Path projectRoot = Paths.get(System.getProperty("user.dir")).getParent();
        Path avatarsDir = projectRoot.resolve("avatars");

        File outputFolder = avatarsDir.toFile();
        if (!outputFolder.exists()) {
            outputFolder.mkdirs();
        }
            
        // Ask user which mode
        System.out.println("Select mode:");
        System.out.println("1 - Manual input");
        System.out.println("2 - Batch from text file");
        System.out.print("Enter 1 or 2: ");
        String choice = scanner.nextLine().trim();

        //Create a List to hold instances of Runnable tasks for threading
        List<ImageJob> jobsList = new ArrayList<>();


        try {
            //Decide how to collect jobs based on user choice
            switch (choice) {
                case "1" -> collectManualJobs(scanner, jobsList);
                case "2" -> collectBatchJobs(scanner, jobsList);
                default -> {
                    System.out.println("Invalid option. Exiting");
                    return;
                }
            }
            //Run the collected jobs in parallel
            runParallelProcessing(jobsList, outputFolder);

        } finally {
            scanner.close();
        }
    }   

    //Collect jobs for manual mode
    private static void collectManualJobs(Scanner scanner, List<ImageJob> jobsList) {
        //Get number of images to process
        System.out.print("How many images? ");
        int count = Integer.parseInt(scanner.nextLine());

        for (int i = 1; i <= count; i++) {
            System.out.println("\nImage " + i);

            System.out.print("Image URL: ");
            String url = scanner.nextLine().trim();

            System.out.print("Output name (without extension): ");
            String name = scanner.nextLine().trim();

            //Add job to the job list
            jobsList.add(new ImageJob(url, name));
        }
    }


    //Collect jobs for batch mode
    private static void collectBatchJobs(Scanner scanner, List<ImageJob> jobs) {
        //Get path to text file with urls in it
        System.out.print("Path to URL file: ");
        File file = new File(scanner.nextLine().trim());    //Read URLs from file

        if (!file.exists()) {
            throw new RuntimeException("File not found.");
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int counter = 1;

            //Read url on each line until EOF
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    //Add job with a default name based on counter to the job list
                    jobs.add(new ImageJob(line, "avatar_" + counter));
                    counter++;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + e.getMessage());
        }
    }

    //Multi-threaded processing of image jobs
    private static void runParallelProcessing(List<ImageJob> jobs, File outputFolder) {
        //Create a fixed thread pool of 4 threads that persist for the duration of processing
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        //Future objects to track task completion. Futures can be used to check if a task is done or to get its result
        //The ? wildcard means void, since we dont expect any return value from the tasks. We only care about completion or failure
        List<Future<?>> futures = new ArrayList<>();

        for (ImageJob job : jobs) {
            //Submit each job to the executor for parallel processing
            //This wraps the processJob method in a lambda to match the Callable/Runnable signature
            //Then places it in the executor's task queue

            //Every worker thread will pick up jobs as they become available 
            //and work on their own url, create their own image, and save it independently
            Future<?> future = executor.submit(() -> processJob(job, outputFolder));   
            //We will use the future list to monitor task completion
            futures.add(future);
        }

        //Wait for all submitted tasks to complete before proceeding
        for (Future<?> future : futures) {
            try {
                //future.get() will block the main thread from continuing until the worker thread's tasks are flagged complete
                future.get();
            } catch (ExecutionException e) {
                System.err.println("A job failed:");
                e.getCause().printStackTrace();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Main thread interrupted while waiting for jobs.");
            }
        }

        //Stop accepting new tasks and shutdown the executor
        executor.shutdown();

        //While we wait for existing tasks to finish, we can set a timeout to avoid waiting indefinitely as a safeguard
        try {
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("\nAll jobs completed.");
    }

    //Process a single image job
    private static void processJob(ImageJob job, File outputFolder) {
        try {
            File outputFile = getUniqueFile(outputFolder, job.imageName, "png");

            //Begin timing the operation
            long start = System.nanoTime();
            BufferedImage avatar = DiscordImageResizer.downloadAndResize(job.url);
            long durationMs = (System.nanoTime() - start) / 1_000_000;  //Record time elapsed in milliseconds

            DiscordImageResizer.saveImage(avatar, outputFile);

            System.out.println(
                "Saved: " + outputFile.getName() +
                " in (" + durationMs + " ms)"
            );

        } catch (IOException e) {
            throw new RuntimeException("Failed processing: " + job.url, e); //Allow future.get() to catch and report
        }
    }


    //Helper method to generate a unique file name in the specified folder
    private static File getUniqueFile(File folder, String baseName, String extension) {
        File file = new File(folder, baseName + "." + extension);
        int counter = 1;
        while (file.exists()) {
            file = new File(folder, baseName + "_" + counter + "." + extension);
            counter++;
        }
        return file;
    }
}

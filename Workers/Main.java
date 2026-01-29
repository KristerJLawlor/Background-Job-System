import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        //Attempt to get image URL from user and process it
        Scanner scanner = new Scanner(System.in);

        try {
            // Ask user which mode
            System.out.println("Select mode:");
            System.out.println("1 - Manual input");
            System.out.println("2 - Batch from text file");
            System.out.print("Enter 1 or 2: ");
            String choice = scanner.nextLine().trim();

            // Output folder
            File outputFolder = new File("avatars");
            if (!outputFolder.exists()) {
                outputFolder.mkdir();
            }

            if (choice.equals("1")) {
                runManualMode(scanner, outputFolder);
            } else if (choice.equals("2")) {
                runBatchMode(scanner, outputFolder);
            } else {
                System.out.println("Invalid choice. Exiting.");
            }

        } finally {
            scanner.close();
        }

    }   

    private static void runManualMode(Scanner scanner, File outputFolder) {
            //Ask the user how many images they want to process
            System.out.print("How many images do you want to process? ");
            int count = Integer.parseInt(scanner.nextLine());


            if (!outputFolder.exists()) {
                outputFolder.mkdir();
            }

            for (int i = 1; i <= count; i++) 
            {
                System.out.println("\nProcessing image " + i + " of " + count);

                //Get image URL from user
                System.out.print("Enter image URL: ");
                String imageUrl = scanner.nextLine().trim();

                //Get desired name for output image
                System.out.print("Name output image (without extension): ");
                String imageName = scanner.nextLine().trim();
                //Generate unique file name to avoid overwriting and append with png extension
                File outputFile = getUniqueFile(outputFolder, imageName, "png");

                processImage(imageUrl, outputFile);
            }
    }

    private static void runBatchMode(Scanner scanner, File outputFolder) {

        //Get path to text file with URLs
        System.out.print("Enter path to text file with URLs: ");
        String path = scanner.nextLine().trim();
        File urlFile = new File(path);

        //return if file doesnt exist
        if (!urlFile.exists()) {
            System.err.println("File not found: " + path);
            return;
        }

        //Read each line from the file and process as image URL
        try (BufferedReader reader = new BufferedReader(new FileReader(urlFile))) {
            String line;
            int count = 1;
            //Process each URL in the file until EOF
            while ((line = reader.readLine()) != null) {
                String imageUrl = line.trim();
                if (imageUrl.isEmpty()) continue;    //Skip empty lines

                String imageName = "avatar_" + count;
                File outputFile = getUniqueFile(outputFolder, imageName, "png");

                System.out.println("\nProcessing URL: " + imageUrl);
                processImage(imageUrl, outputFile);
                count++;
            }

            System.out.println("\nBatch processing completed!");

        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
        }
    }

    private static void processImage(String imageUrl, File outputFile) {
        try {
                //Begin benchmarking time
                long startTime = System.nanoTime();
                //Download and resize the image using our DiscordImageResizer class
                BufferedImage imageResult = DiscordImageResizer.downloadAndResize(imageUrl);
                //End benchmarking time
                long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                
                //Save the processed image to disk
                DiscordImageResizer.saveImage(imageResult, outputFile);
                System.out.println("Image saved successfully to: " + outputFile.getAbsolutePath());

                System.out.println("Resize + download took: " + durationMs + " ms");
        } catch (IOException e) {
            System.err.println("Failed to process URL: " + imageUrl + " -> " + e.getMessage());
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

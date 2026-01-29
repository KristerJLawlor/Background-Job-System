import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        //Attempt to get image URL from user and process it
        Scanner scanner = new Scanner(System.in);

        try {

            //Ask the user how many images they want to process
            System.out.print("How many images do you want to process? ");
            int count = Integer.parseInt(scanner.nextLine());

            // Create the output folder if it doesn't exist
            File outputFolder = new File("avatars");
            if (!outputFolder.exists()) {
                outputFolder.mkdir();
            }

            for (int i = 1; i <= count; i++) 
            {
                System.out.println("\nProcessing image " + i + " of " + count);

                //Get image URL from user
                System.out.print("Enter image URL: ");
                String imageUrl = scanner.nextLine();

                //Get desired name for output image
                System.out.print("Name output image (without extension): ");
                String imageName = scanner.nextLine();
                //Generate unique file name to avoid overwriting and append with png extension
                File outputFile = getUniqueFile(outputFolder, imageName, "png");

                /*
                Delete above and use commented code for command line arguments instead, 
                which is more useful for automated scripts
                ----------------------------------------------------------------------
                if (args.length != 2) {
                    System.out.println("Usage: java Main <image_url> <output_file_name>");
                    return;
                }

                String url = args[0];
                File outputFile = new File(args[1] + ".png");
                ----------------------------------------------------------------------
                use like this in command line: java Main https://example.com/image.jpg my_avatar

                */

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
            
            }

        } catch (IOException e) {
            System.err.println("Failed to process image: " + e.getMessage());
        } catch (NumberFormatException e) {
            System.err.println("Invalid number entered.");
        } finally{
            scanner.close();
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

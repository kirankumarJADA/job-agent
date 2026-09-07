# ATS Resume & Application Generator Workflow

This project is configured to automatically generate ATS-compliant resumes, cover letters, and downloadable PDFs whenever a Master CV and Job Description (JD) are provided.

## DEFAULT OUTPUT STORAGE LOCATION
**ALWAYS save ONLY the final generated PDF files directly to:**
`C:\Users\jadak\OneDrive\Desktop\uk resume\New\`

## Operating Guidelines

When the user shares their Master CV and a Job Description:

1. **Extract & Analyze Keywords**:
   - Extract hard skills, soft skills, tools, domain keywords, and key responsibilities from the target JD.
   - Run ATS compatibility matching against the Master CV.

2. **Generate ATS-Optimized Resume**:
   - **Structure**: Clean single-column layout, standard headers (Professional Summary, Technical Skills, Professional Experience, Education, Projects).
   - **Bullet Points**: Use the Google XYZ format: *Accomplished [X] as measured by [Y], by doing [Z]*. Incorporate critical keywords naturally with quantifiable metrics.
   - **ATS Safety**: No tables, no multi-column grids, no headers/footers, standard text formatting.

3. **Generate Matching Cover Letter / Cover Sheet**:
   - Compelling opening highlighting alignment with the company and role.
   - 2-3 body paragraphs demonstrating top 3 relevant achievements mapped directly to key JD requirements.
   - Confident call to action and professional sign-off.

4. **Output Storage (PDF Only)**:
   - Save ONLY the compiled PDF files to `C:\Users\jadak\OneDrive\Desktop\uk resume\New\`:
     - `C:\Users\jadak\OneDrive\Desktop\uk resume\New\<Candidate_Name>_Resume.pdf`
     - `C:\Users\jadak\OneDrive\Desktop\uk resume\New\<Candidate_Name>_Cover_Letter.pdf`
   - Clean up any temporary HTML / MD files automatically so only the clean PDFs remain.
   - Display clickable download links to the PDF files in chat.

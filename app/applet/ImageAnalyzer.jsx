import React, { useState, useRef } from "react";

/**
 * ImageAnalyzer - A premium, self-contained React component for uploading images
 * and performing visual analysis using the Google Gemini AI API.
 *
 * Designed with a dark cosmic theme, interactive drag-and-drop zone,
 * custom prompts, action presets, and dynamic loading states.
 */
export default function ImageAnalyzer() {
  const [selectedImage, setSelectedImage] = useState(null);
  const [imagePreview, setImagePreview] = useState("");
  const [prompt, setPrompt] = useState("");
  const [analysisResult, setAnalysisResult] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);

  const fileInputRef = useRef(null);

  // Quick prompt presets to help the user start instantly
  const promptPresets = [
    { label: "Describe Image", text: "Provide a detailed, atmospheric description of this visual scene." },
    { label: "Extract Text / OCR", text: "Identify and extract all legible text, signs, or handwriting in this image." },
    { label: "Identify Objects", text: "List and describe the primary objects and elements in this image." },
    { label: "Analyze Mood & Colors", text: "What are the core emotional vibes, primary color schemes, and visual styles?" },
  ];

  // Handle image file selection
  const handleFileChange = (e) => {
    const file = e.target.files[0];
    processFile(file);
  };

  // Convert and set up preview for selected file
  const processFile = (file) => {
    if (!file) return;

    if (!file.type.startsWith("image/")) {
      setError("Please select a valid image file (PNG, JPEG, WEBP).");
      return;
    }

    setError("");
    setSelectedImage(file);

    // Create base64 preview
    const reader = new FileReader();
    reader.onloadend = () => {
      setImagePreview(reader.result);
    };
    reader.readAsDataURL(file);
  };

  // Drag and drop handlers
  const handleDragOver = (e) => {
    e.preventDefault();
  };

  const handleDrop = (e) => {
    e.preventDefault();
    const file = e.dataTransfer.files[0];
    processFile(file);
  };

  // Clear current image and result
  const handleClear = () => {
    setSelectedImage(null);
    setImagePreview("");
    setAnalysisResult("");
    setError("");
    if (fileInputRef.current) {
      fileInputRef.current.value = "";
    }
  };

  // Triggers visual analysis using the Gemini API via REST
  const analyzeImage = async () => {
    if (!selectedImage) {
      setError("Please upload an image first.");
      return;
    }

    const activePrompt = prompt.trim() || "Describe what you see in this image in detail.";
    setIsLoading(true);
    setError("");
    setAnalysisResult("");

    try {
      // 1. Resolve API key from environment variable (Vite or Create React App)
      const apiKey = 
        import.meta.env?.VITE_GEMINI_API_KEY || 
        process.env?.REACT_APP_GEMINI_API_KEY || 
        ""; // Users can configure this in their .env configurations

      if (!apiKey) {
        throw new Error(
          "Gemini API key is not configured. Please add VITE_GEMINI_API_KEY to your environment variables."
        );
      }

      // 2. Remove base64 metadata prefix (e.g. 'data:image/jpeg;base64,')
      const base64Data = imagePreview.split(",")[1];
      const mimeType = selectedImage.type;

      // 3. Prepare body payloads matching Gemini's REST Schema
      const requestPayload = {
        contents: [
          {
            parts: [
              { text: activePrompt },
              {
                inlineData: {
                  mimeType: mimeType,
                  data: base64Data,
                },
              },
            ],
          },
        ],
        generationConfig: {
          temperature: 0.4,
          topP: 0.95,
        },
      };

      // 4. Request Google Gemini API (model: gemini-3.5-flash)
      const response = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=${apiKey}`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify(requestPayload),
        }
      );

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData?.error?.message || `API Error (HTTP ${response.status})`);
      }

      const responseJson = await response.json();
      const generatedText = responseJson?.candidates?.[0]?.content?.parts?.[0]?.text;

      if (!generatedText) {
        throw new Error("No analysis returned from Gemini.");
      }

      setAnalysisResult(generatedText);
    } catch (err) {
      console.error("Gemini Analysis Error:", err);
      setError(err.message || "An unexpected error occurred during analysis.");
    } finally {
      setIsLoading(false);
    }
  };

  // Helper to copy text output
  const copyToClipboard = () => {
    if (!analysisResult) return;
    navigator.clipboard.writeText(analysisResult);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="w-full max-w-6xl mx-auto p-4 md:p-8 bg-slate-950 text-slate-100 min-h-screen font-sans">
      
      {/* Header Area */}
      <div className="mb-8 text-center md:text-left">
        <div className="flex items-center justify-center md:justify-start gap-3 mb-2">
          <div className="p-2 bg-indigo-600/20 text-indigo-400 rounded-lg">
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor" className="w-6 h-6">
              <path strokeLinecap="round" strokeLinejoin="round" d="M9.813 15.904L9 21l3.597-2.399L16.197 21l-.813-5.096c-.066-.412.083-.827.398-1.097L19.5 11l-5.114-.423a1.21 1.21 0 01-.992-.816L12 5 .992 9.761a1.21 1.21 0 01-.992.816L5.5 11l3.915 3.807c.315.27.464.685.398 1.097z" />
            </svg>
          </div>
          <h1 className="text-3xl font-extrabold bg-gradient-to-r from-indigo-400 via-purple-400 to-pink-400 bg-clip-text text-transparent">
            Scribe Vision Hub
          </h1>
        </div>
        <p className="text-slate-400 text-sm md:text-base max-w-2xl">
          Instantly unpack visual narratives, extract handwritten texts, and analyze details using Google Gemini's multimodal intelligence.
        </p>
      </div>

      {/* Main Two-Panel Layout */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
        
        {/* Left Panel: Inputs & Selection */}
        <div className="lg:col-span-5 space-y-6">
          
          {/* File Picker Zone */}
          <div className="space-y-2">
            <label className="block text-sm font-semibold text-slate-300">
              Source Image
            </label>
            
            {!imagePreview ? (
              <div
                onDragOver={handleDragOver}
                onDrop={handleDrop}
                onClick={() => fileInputRef.current?.click()}
                className="border-2 border-dashed border-slate-800 hover:border-indigo-500/50 hover:bg-indigo-950/10 cursor-pointer transition-all duration-300 rounded-2xl h-64 flex flex-col items-center justify-center p-6 text-center group"
              >
                <input
                  type="file"
                  ref={fileInputRef}
                  onChange={handleFileChange}
                  accept="image/*"
                  className="hidden"
                />
                <div className="p-4 bg-slate-900 rounded-full text-slate-400 group-hover:text-indigo-400 group-hover:scale-110 transition-all duration-300 mb-4">
                  <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-8 h-8">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M12 16.5V9.75m0 0l3 3m-3-3l-3 3M6.75 19.5a4.5 4.5 0 01-1.41-8.775 5.25 5.25 0 0110.233-2.33 3 3 0 013.758 3.848A3.752 3.752 0 0118 19.5H6.75z" />
                  </svg>
                </div>
                <h3 className="font-semibold text-slate-200">Drag & drop your image</h3>
                <p className="text-xs text-slate-500 mt-1 max-w-xs">
                  Or click to browse from device. Supports JPEG, PNG, WEBP.
                </p>
              </div>
            ) : (
              <div className="relative rounded-2xl overflow-hidden border border-slate-800 bg-slate-900 h-64 flex items-center justify-center">
                <img
                  src={imagePreview}
                  alt="Selected preview"
                  className="w-full h-full object-contain"
                />
                
                {/* Image Actions Overlay */}
                <button
                  type="button"
                  onClick={handleClear}
                  className="absolute top-3 right-3 p-2 bg-slate-950/80 hover:bg-red-950 text-slate-300 hover:text-red-400 rounded-full transition-all duration-200 backdrop-blur-md border border-slate-800"
                  title="Remove image"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor" className="w-4 h-4">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>
            )}
          </div>

          {/* User Custom Direction Input */}
          <div className="space-y-3">
            <div className="flex justify-between items-center">
              <label className="block text-sm font-semibold text-slate-300">
                AI Analysis Request
              </label>
              <span className="text-xs text-slate-500 italic">Optional</span>
            </div>
            
            <textarea
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              placeholder="Give Gemini instructions (e.g., 'Draft a cyberpunk story opening based on this scene...')"
              className="w-full min-h-[110px] p-4 bg-slate-900 border border-slate-800 rounded-xl text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-indigo-500 focus:border-indigo-500 transition-all text-sm resize-none"
            />
            
            {/* Presets Grid */}
            <div className="space-y-2">
              <span className="block text-xs font-semibold text-slate-500 uppercase tracking-wider">
                Quick Presets
              </span>
              <div className="grid grid-cols-2 gap-2">
                {promptPresets.map((preset, index) => (
                  <button
                    key={index}
                    type="button"
                    onClick={() => setPrompt(preset.text)}
                    className="p-2.5 bg-slate-900/50 hover:bg-slate-900 border border-slate-800 hover:border-slate-700 rounded-xl text-left text-xs text-slate-300 transition-all hover:scale-[1.01]"
                  >
                    <span className="font-semibold text-indigo-400 block mb-0.5">{preset.label}</span>
                    <span className="text-[10px] text-slate-500 line-clamp-1">{preset.text}</span>
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* Trigger Action */}
          <button
            type="button"
            onClick={analyzeImage}
            disabled={isLoading || !selectedImage}
            className={`w-full h-12 rounded-xl flex items-center justify-center gap-2 text-sm font-bold tracking-wide transition-all ${
              isLoading || !selectedImage
                ? "bg-slate-800 text-slate-500 cursor-not-allowed border border-slate-800"
                : "bg-indigo-600 hover:bg-indigo-500 text-white shadow-lg shadow-indigo-600/10 hover:scale-[1.01]"
            }`}
          >
            {isLoading ? (
              <>
                <svg className="animate-spin h-5 w-5 text-white" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                </svg>
                <span>Analyzing Visuals...</span>
              </>
            ) : (
              <>
                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor" className="w-5 h-5">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M15.59 14.37a6 6 0 01-5.84 7.38v-4.8m5.84-2.58a14.98 14.98 0 006.16-12.12A14.98 14.98 0 009.631 8.41m5.96 5.96a14.926 14.926 0 01-5.841 2.58m-.119-8.54a6 6 0 00-7.381 5.84h4.8m2.581-5.84a14.927 14.927 0 00-2.58 5.84m2.699 2.7c-.103.021-.207.041-.311.06a15.09 15.09 0 01-2.448-2.448 14.9 14.9 0 01.06-.312m-2.24 2.39a4.493 4.493 0 00-1.757 4.306 4.493 4.493 0 004.306-1.758M16.5 9a1.5 1.5 0 11-3 0 1.5 1.5 0 013 0z" />
                </svg>
                <span>Run Vision Analysis</span>
              </>
            )}
          </button>
          
        </div>

        {/* Right Panel: Result Screen */}
        <div className="lg:col-span-7 h-full">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 min-h-[500px] flex flex-col justify-between relative overflow-hidden">
            
            {/* Background ambient light */}
            <div className="absolute top-0 right-0 w-64 h-64 bg-indigo-500/5 blur-[120px] rounded-full pointer-events-none" />
            <div className="absolute bottom-0 left-0 w-64 h-64 bg-pink-500/5 blur-[120px] rounded-full pointer-events-none" />

            <div>
              {/* Output Header */}
              <div className="flex justify-between items-center mb-6 pb-4 border-b border-slate-800">
                <div className="flex items-center gap-2">
                  <span className="h-2 w-2 rounded-full bg-indigo-400 animate-pulse" />
                  <h2 className="text-sm font-semibold tracking-wider text-slate-300 uppercase">
                    AI Analysis Output
                  </h2>
                </div>
                
                {analysisResult && (
                  <button
                    type="button"
                    onClick={copyToClipboard}
                    className="flex items-center gap-1.5 px-3 py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-lg text-xs font-semibold transition-all"
                  >
                    {copied ? (
                      <>
                        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={2.5} stroke="currentColor" className="w-3.5 h-3.5 text-emerald-400">
                          <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 12.75l6 6 9-13.5" />
                        </svg>
                        <span className="text-emerald-400">Copied!</span>
                      </>
                    ) : (
                      <>
                        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor" className="w-3.5 h-3.5">
                          <path strokeLinecap="round" strokeLinejoin="round" d="M15.666 3.888A2.25 2.25 0 0013.5 2.25h-3c-1.03 0-1.9.693-2.166 1.638m7.332 0c.055.194.084.4.084.612v0a.75.75 0 01-.75.75H9a.75.75 0 01-.75-.75v0c0-.212.03-.418.084-.612m7.332 0c.009-.035.018-.07.025-.106A2.25 2.25 0 0113.5 2.25h-3c-1.011 0-1.865.661-2.14 1.588m10.03 12.01l-1.012 1.011a1.5 1.5 0 01-2.122 0L10.5 13.5M9 16.5H4.5A2.25 2.25 0 012.25 14.25v-9c0-1.243 1.007-2.25 2.25-2.25h15c1.243 0 2.25 1.007 2.25 2.25v9a2.25 2.25 0 01-2.25 2.25H15M9 16.5v1.5M9 16.5H4.5" />
                        </svg>
                        <span>Copy Response</span>
                      </>
                    )}
                  </button>
                )}
              </div>

              {/* Error State */}
              {error && (
                <div className="flex items-start gap-3 p-4 bg-red-950/20 border border-red-900/50 rounded-xl text-red-300 text-sm mb-4">
                  <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor" className="w-5 h-5 shrink-0 text-red-400">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" />
                  </svg>
                  <div>
                    <span className="font-bold block mb-0.5">Configuration Alert</span>
                    {error}
                  </div>
                </div>
              )}

              {/* Dynamic Content Body */}
              {isLoading ? (
                <div className="flex flex-col items-center justify-center py-20 text-center space-y-4">
                  {/* Elegant layered pulse rings */}
                  <div className="relative flex items-center justify-center">
                    <span className="absolute inline-flex h-16 w-16 rounded-full bg-indigo-500/20 animate-ping" />
                    <span className="relative inline-flex rounded-full h-12 w-12 bg-indigo-600/30 flex items-center justify-center">
                      <svg className="animate-spin h-6 w-6 text-indigo-400" fill="none" viewBox="0 0 24 24">
                        <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                        <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                      </svg>
                    </span>
                  </div>
                  <div>
                    <h4 className="font-semibold text-slate-200">Processing with Gemini</h4>
                    <p className="text-xs text-slate-500 mt-1 max-w-sm">
                      Extracting structural components, lighting details, and parsing contextual visual semantics...
                    </p>
                  </div>
                </div>
              ) : analysisResult ? (
                <div className="text-slate-300 text-sm leading-relaxed whitespace-pre-wrap font-sans max-h-[420px] overflow-y-auto pr-2 custom-scrollbar">
                  {analysisResult}
                </div>
              ) : (
                <div className="flex flex-col items-center justify-center py-24 text-center text-slate-500 space-y-3">
                  <div className="p-3 bg-slate-800/30 text-slate-600 rounded-full">
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-8 h-8">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M2.036 12.322a1.012 1.012 0 010-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.43 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178z" />
                      <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                    </svg>
                  </div>
                  <div className="max-w-xs">
                    <h4 className="font-semibold text-slate-400">Ready to Analyze</h4>
                    <p className="text-xs text-slate-600 mt-1">
                      Choose an image, select a prompt, and trigger Gemini to see detailed AI insights.
                    </p>
                  </div>
                </div>
              )}
            </div>

            {/* Footer Tip */}
            <div className="pt-4 border-t border-slate-800 text-[11px] text-slate-600 flex items-center gap-1.5 justify-center md:justify-start">
              <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor" className="w-3.5 h-3.5 text-indigo-500 shrink-0">
                <path strokeLinecap="round" strokeLinejoin="round" d="M11.25 11.25l.041-.02a.75.75 0 111.083 1.083l-.042.022M12 9h.008v.008H12V9zm.75 0a.75.75 0 11-1.5 0 .75.75 0 011.5 0z" />
              </svg>
              <span>Powered by gemini-3.5-flash with low-latency multimodal token decoding.</span>
            </div>

          </div>
        </div>

      </div>
    </div>
  );
}

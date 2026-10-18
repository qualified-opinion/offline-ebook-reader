document.getElementById('fileInput').addEventListener('change', function(e) {
  const file = e.target.files[0];
  if (!file) return;

  const reader = new FileReader();
  const readerDiv = document.getElementById('reader');

  reader.onload = function(event) {
    if (file.type === 'text/plain' || file.name.endsWith('.txt')) {
      readerDiv.textContent = event.target.result;
    } else if (file.name.endsWith('.html')) {
      readerDiv.innerHTML = event.target.result;
    } else {
      readerDiv.innerHTML = `<p>File loaded: ${file.name}</p><p>For full EPUB/PDF support, integrate a library like epub.js or PDF.js.</p><p>Size: ${(file.size / 1024).toFixed(1)} KB</p>`;
    }
  };

  if (file.type.startsWith('text/') || file.name.endsWith('.txt') || file.name.endsWith('.html')) {
    reader.readAsText(file);
  } else {
    reader.readAsArrayBuffer(file);
    reader.onload = function() {
      readerDiv.innerHTML = `<p>Binary file loaded: ${file.name}</p><p>Ready for processing with a reader library.</p>`;
    };
  }
});

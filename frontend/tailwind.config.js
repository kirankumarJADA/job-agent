/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        /**
         * Brand palette modelled on the Tsenta dashboard reference:
         * warm off-white canvas (#faf9f5), deep forest green primary
         * (#15362b), soft green selected surfaces (#e7f0ea), white cards,
         * warm light borders and near-black ink text.
         */
        forest: {
          50: '#f3f7f5',
          100: '#e7f0ea',
          200: '#cfe0d6',
          300: '#a9c8b8',
          400: '#7dab97',
          500: '#588d78',
          600: '#40715f',
          700: '#2f5a4a',
          800: '#20473a',
          900: '#15362b',
          950: '#0c211a',
        },
        cream: {
          50: '#faf9f5',
          100: '#f4f2ea',
          200: '#eae7db',
          300: '#ddd8c6',
        },
        ink: {
          DEFAULT: '#141413',
          soft: '#44423d',
          muted: '#6f6d66',
          faint: '#a5a29a',
        },
        surface: {
          DEFAULT: '#ffffff',
          raised: '#fffefb',
          sunken: '#f7f5f0',
        },
        line: '#e7e4da',
      },
      boxShadow: {
        card: '0 1px 2px rgba(20, 20, 19, 0.04), 0 1px 3px rgba(20, 20, 19, 0.06)',
        raise: '0 4px 14px rgba(21, 54, 43, 0.10)',
        pop: '0 18px 44px rgba(20, 20, 19, 0.12)',
      },
      maxWidth: {
        shell: '72rem',
      },
    },
  },
  plugins: [],
}
